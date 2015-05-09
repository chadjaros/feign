package feign;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import lombok.Data;
import lombok.experimental.Accessors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FeignClient<T> {

    private final FeignMaker.Builder.BuiltData data;
    private final Target target;
    private final Map<String, MethodMetadata> metadata;
    private final Map<String, ReflectiveFeignMaker.BuildTemplateByResolvingArgs> buildTemplates;
    private final Map<String, Method> methods;

    private T theDefault;

    public FeignClient(Target target, FeignMaker.Builder.BuiltData data) {
        this.target = target;
        this.data = data;

        List<MethodMetadata> metadataList = data.contract().parseAndValidatateMetadata(target.type());

        this.metadata = Maps.newHashMap();
        for (MethodMetadata md : metadataList) {
            this.metadata.put(md.configKey(), md);
        }

        this.buildTemplates = new ReflectiveFeignMaker.ParseHandlersByName(data.encoder()).apply(target, metadataList);

        this.methods = Maps.newHashMap();
        for (Method method : target.type().getDeclaredMethods()) {
            if (method.getDeclaringClass() == Object.class)
                continue;

            methods.put(FeignMaker.configKey(method), method);
        }
    }

    public T go() {
        return proxy(data.headerSuppliers(), data.requestInterceptors(), data.loadBalancerKey());
    }

    public RequestBuilder request() {
        return new RequestBuilder(data.headerSuppliers(), data.requestInterceptors(), data.loadBalancerKey());
    }

    public AsyncRequestBuilder asyncRequest() {
        return new AsyncRequestBuilder(data.headerSuppliers(), data.requestInterceptors(), data.loadBalancerKey());
    }

    @SuppressWarnings("unchecked")
    protected T proxy(List<HeaderSupplier> suppliers, List<RequestInterceptor> interceptors, Object loadBalancerKey) {

        if(suppliers.isEmpty() &&
                interceptors.equals(data.requestInterceptors()) &&
                loadBalancerKey == data.loadBalancerKey() &&
                theDefault != null) {
            return theDefault;
        }

        if(!suppliers.isEmpty()) {
            final Map<String, Collection<String>> headers = Maps.newHashMap();
            for (HeaderSupplier supplier : suppliers) {
                Map<String, Collection<String>> suppliedHeaders = supplier.get();
                for (String key : suppliedHeaders.keySet()) {
                    if (headers.containsKey(key)) {
                        headers.get(key).addAll(suppliedHeaders.get(key));
                    } else {
                        headers.put(key, suppliedHeaders.get(key));
                    }
                }
            }

            interceptors.add(new RequestInterceptor() {
                @Override
                public void apply(RequestTemplate template) {
                    for (String key : headers.keySet()) {
                        template.header(key, headers.get(key));
                    }
                }
            });
        }

        // Update the target with the given load balancer key if possible
        Target localTarget = target;
        if(loadBalancerKey != data.loadBalancerKey() && localTarget instanceof LoadBalancerAwareTarget) {
            localTarget = ((LoadBalancerAwareTarget)localTarget).loadBalancerKey(loadBalancerKey);
        }

        SynchronousMethodHandler.Factory synchronousMethodHandlerFactory =
                new SynchronousMethodHandler.Factory(
                        data.client(),
                        data.retryer(),
                        interceptors,
                        data.logger(),
                        data.logLevel()
                );

        Map<Method, InvocationHandlerFactory.MethodHandler> methodToHandler = new LinkedHashMap<Method, InvocationHandlerFactory.MethodHandler>();
        for (String key : this.methods.keySet()) {
            InvocationHandlerFactory.MethodHandler handler = synchronousMethodHandlerFactory.create(
                    localTarget,
                    metadata.get(key),
                    buildTemplates.get(key),
                    data.options(),
                    data.decoder(),
                    data.errorDecoder()
            );
            methodToHandler.put(this.methods.get(key), handler);
        }

        InvocationHandler handler = data.invocationHandlerFactory().create(localTarget, methodToHandler);
        T instance =  (T) Proxy.newProxyInstance(localTarget.type().getClassLoader(), new Class<?>[]{localTarget.type()}, handler);

        if(suppliers.isEmpty() && interceptors.equals(data.requestInterceptors()) && loadBalancerKey == data.loadBalancerKey()) {
            theDefault = instance;
        }

        return instance;
    }

    @Data
    @Accessors(chain = true, fluent = true)
    class RequestBuilder {

        private List<HeaderSupplier> headerSuppliers;
        private List<RequestInterceptor> builderInterceptors;
        private Object loadBalancerKey;

        public RequestBuilder(Collection<HeaderSupplier> suppliers, Collection<RequestInterceptor> interceptors, Object loadBalancerKey) {
            headerSuppliers = Lists.newArrayList(suppliers);
            builderInterceptors = Lists.newArrayList(interceptors);
            this.loadBalancerKey = loadBalancerKey;
        }

        private RequestBuilder addHeaderSupplier(HeaderSupplier supplier) {
            headerSuppliers.add(supplier);
            return this;
        }

        private RequestBuilder addInterceptor(RequestInterceptor interceptor) {
            builderInterceptors.add(interceptor);
            return this;
        }

        public T go() {
            return proxy(headerSuppliers, builderInterceptors, loadBalancerKey);
        }
    }

    @Data
    @Accessors(chain = true, fluent = true)
    class AsyncRequestBuilder {

        private List<HeaderSupplier> headerSuppliers;
        private List<RequestInterceptor> builderInterceptors;
        private Object loadBalancerKey;

        public AsyncRequestBuilder(Collection<HeaderSupplier> suppliers, Collection<RequestInterceptor> interceptors, Object loadBalancerKey) {
            headerSuppliers = Lists.newArrayList(suppliers);
            builderInterceptors = Lists.newArrayList(interceptors);
            this.loadBalancerKey = loadBalancerKey;
        }

        private AsyncRequestBuilder addHeaderSupplier(HeaderSupplier supplier) {
            headerSuppliers.add(supplier);
            return this;
        }

        private AsyncRequestBuilder addInterceptor(RequestInterceptor interceptor) {
            builderInterceptors.add(interceptor);
            return this;
        }

        public <U> ListenableFuture<U> go(Function<T, U> function) {

            SettableFuture<U> future = SettableFuture.create();
            T instance = proxy(headerSuppliers, builderInterceptors, loadBalancerKey);

            {   //TODO execute asynchronously
                future.set(function.apply(instance));
            }

            return future;
        }
    }
}
