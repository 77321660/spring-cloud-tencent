/*
 * Tencent is pleased to support the open source community by making Spring Cloud Tencent available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.cloud.polaris.router;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.tencent.cloud.common.constant.RouterConstants;
import com.tencent.cloud.common.metadata.MetadataContext;
import com.tencent.cloud.common.metadata.MetadataContextHolder;
import com.tencent.cloud.common.pojo.PolarisServiceInstance;
import com.tencent.cloud.common.util.JacksonUtils;
import com.tencent.cloud.polaris.loadbalancer.LoadBalancerUtils;
import com.tencent.cloud.polaris.router.resttemplate.PolarisLoadBalancerRequest;
import com.tencent.cloud.polaris.router.spi.RouterRequestInterceptor;
import com.tencent.cloud.polaris.router.spi.RouterResponseInterceptor;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.pojo.ServiceInfo;
import com.tencent.polaris.api.pojo.ServiceInstances;
import com.tencent.polaris.router.api.core.RouterAPI;
import com.tencent.polaris.router.api.rpc.ProcessRoutersRequest;
import com.tencent.polaris.router.api.rpc.ProcessRoutersResponse;
import reactor.core.publisher.Flux;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequestContext;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.util.CollectionUtils;

import static com.tencent.cloud.common.constant.ContextConstant.UTF_8;

/**
 * Service routing entrance.
 *
 * Rule routing needs to rely on request parameters for server filtering.
 * The interface cannot obtain the context object of the request granularity,
 * so the routing capability cannot be achieved through ServerListFilter.
 *
 * And {@link PolarisRouterServiceInstanceListSupplier#get(Request)} provides the ability to pass in http headers,
 * so routing capabilities are implemented through IRule.
 *
 * @author Haotian Zhang, lepdou
 */
public class PolarisRouterServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

	private final RouterAPI routerAPI;
	private final List<RouterRequestInterceptor> requestInterceptors;
	private final List<RouterResponseInterceptor> responseInterceptors;

	public PolarisRouterServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
			RouterAPI routerAPI, List<RouterRequestInterceptor> requestInterceptors,
			List<RouterResponseInterceptor> responseInterceptors) {
		super(delegate);
		this.routerAPI = routerAPI;
		this.requestInterceptors = requestInterceptors;
		this.responseInterceptors = responseInterceptors;
	}

	@Override
	public Flux<List<ServiceInstance>> get() {
		throw new PolarisException(ErrorCode.INTERNAL_ERROR, "Unsupported method.");
	}

	@Override
	public Flux<List<ServiceInstance>> get(Request request) {
		// 1. get all servers
		Flux<List<ServiceInstance>> allServers = getDelegate().get();

		// 2. filter by router
		DefaultRequestContext requestContext = (DefaultRequestContext) request.getContext();
		PolarisRouterContext key = null;
		if (requestContext instanceof RequestDataContext) {
			key = buildRouterContext(((RequestDataContext) requestContext).getClientRequest().getHeaders());
		}
		else if (requestContext.getClientRequest() instanceof PolarisLoadBalancerRequest) {
			key = buildRouterContext(((PolarisLoadBalancerRequest<?>) requestContext.getClientRequest()).getRequest()
					.getHeaders());
		}
		return doRouter(allServers, key);
	}

	//set method to public for unit test
	PolarisRouterContext buildRouterContext(HttpHeaders headers) {
		Collection<String> labelHeaderValues = headers.get(RouterConstants.ROUTER_LABEL_HEADER);

		if (CollectionUtils.isEmpty(labelHeaderValues)) {
			return null;
		}

		PolarisRouterContext routerContext = new PolarisRouterContext();

		routerContext.putLabels(PolarisRouterContext.TRANSITIVE_LABELS, MetadataContextHolder.get()
				.getFragmentContext(MetadataContext.FRAGMENT_TRANSITIVE));

		Map<String, String> labelHeaderValuesMap = new HashMap<>();
		try {
			Optional<String> labelHeaderValuesOptional = labelHeaderValues.stream().findFirst();
			if (labelHeaderValuesOptional.isPresent()) {
				String labelHeaderValuesContent = labelHeaderValuesOptional.get();
				labelHeaderValuesMap.putAll(
						JacksonUtils.deserialize2Map(URLDecoder.decode(labelHeaderValuesContent, UTF_8)));
			}
		}
		catch (UnsupportedEncodingException e) {
			throw new RuntimeException("unsupported charset exception " + UTF_8);
		}
		routerContext.putLabels(PolarisRouterContext.ROUTER_LABELS, labelHeaderValuesMap);
		return routerContext;
	}

	Flux<List<ServiceInstance>> doRouter(Flux<List<ServiceInstance>> allServers, PolarisRouterContext routerContext) {
		ServiceInstances serviceInstances = LoadBalancerUtils.transferServersToServiceInstances(allServers);

		// filter instance by routers
		ProcessRoutersRequest processRoutersRequest = buildProcessRoutersRequest(serviceInstances, routerContext);

		// process request interceptors
		processRouterRequestInterceptors(processRoutersRequest, routerContext);

		// process router chain
		ProcessRoutersResponse processRoutersResponse = routerAPI.processRouters(processRoutersRequest);

		// process response interceptors
		processRouterResponseInterceptors(routerContext, processRoutersResponse);

		// transfer polaris server to ServiceInstance
		List<ServiceInstance> filteredInstances = new ArrayList<>();
		ServiceInstances filteredServiceInstances = processRoutersResponse.getServiceInstances();
		for (Instance instance : filteredServiceInstances.getInstances()) {
			filteredInstances.add(new PolarisServiceInstance(instance));
		}
		return Flux.fromIterable(Collections.singletonList(filteredInstances));
	}

	ProcessRoutersRequest buildProcessRoutersRequest(ServiceInstances serviceInstances, PolarisRouterContext key) {
		ProcessRoutersRequest processRoutersRequest = new ProcessRoutersRequest();
		processRoutersRequest.setDstInstances(serviceInstances);
		ServiceInfo serviceInfo = new ServiceInfo();
		serviceInfo.setNamespace(MetadataContext.LOCAL_NAMESPACE);
		serviceInfo.setService(MetadataContext.LOCAL_SERVICE);
		processRoutersRequest.setSourceService(serviceInfo);
		return processRoutersRequest;
	}

	void processRouterRequestInterceptors(ProcessRoutersRequest processRoutersRequest, PolarisRouterContext routerContext) {
		for (RouterRequestInterceptor requestInterceptor : requestInterceptors) {
			requestInterceptor.apply(processRoutersRequest, routerContext);
		}
	}

	private void processRouterResponseInterceptors(PolarisRouterContext routerContext, ProcessRoutersResponse processRoutersResponse) {
		if (!CollectionUtils.isEmpty(responseInterceptors)) {
			for (RouterResponseInterceptor responseInterceptor : responseInterceptors) {
				responseInterceptor.apply(processRoutersResponse, routerContext);
			}
		}
	}

	private Map<String, String> getRouterLabels(PolarisRouterContext key, String type) {
		if (key != null) {
			return key.getLabels(type);
		}
		return Collections.emptyMap();
	}
}
