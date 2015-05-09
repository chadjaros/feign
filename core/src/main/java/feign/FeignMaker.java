package feign;

import com.google.common.collect.Lists;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import lombok.Data;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class FeignMaker {

    public static Builder builder() {
        return new Builder();
    }

    /**
     * <br> Configuration keys are formatted as unresolved <a href= "http://docs.oracle.com/javase/6/docs/jdk/api/javadoc/doclet/com/sun/javadoc/SeeTag.html"
     * >see tags</a>. <br> For example. <ul> <li>{@code Route53}: would match a class such as {@code
     * denominator.route53.Route53} <li>{@code Route53#list()}: would match a method such as {@code
     * denominator.route53.Route53#list()} <li>{@code Route53#listAt(Marker)}: would match a method
     * such as {@code denominator.route53.Route53#listAt(denominator.route53.Marker)} <li>{@code
     * Route53#listByNameAndType(String, String)}: would match a method such as {@code
     * denominator.route53.Route53#listAt(String, String)} </ul> <br> Note that there is no whitespace
     * expected in a key!
     */
    public static String configKey(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getDeclaringClass().getSimpleName());
        builder.append('#').append(method.getName()).append('(');
        for (Class<?> param : method.getParameterTypes()) {
            builder.append(param.getSimpleName()).append(',');
        }
        if (method.getParameterTypes().length > 0) {
            builder.deleteCharAt(builder.length() - 1);
        }
        return builder.append(')').toString();
    }

    /**
     * Returns a new instance of an HTTP API, defined by annotations in the {@link Feign Contract},
     * for the specified {@code target}. You should cache this result.
     */
    public abstract <T> FeignClient<T> newInstance(Target<T> target);

    @Setter
    @Accessors(fluent = true, chain = true)
    public static class Builder {

        static final class Default implements InvocationHandlerFactory {
            @Override
            public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
                return new ReflectiveFeignMaker.FeignMakerInvocationHandler(target, dispatch);
            }
        }

        private final List<RequestInterceptor> requestInterceptors = new ArrayList<RequestInterceptor>();
        private Logger.Level logLevel = Logger.Level.NONE;
        private Contract contract = new Contract.Default();
        private Client client = new Client.Default(null, null);
        private Retryer retryer = new Retryer.Default();
        private Logger logger = new Logger.NoOpLogger();
        private Encoder encoder = new Encoder.Default();
        private Decoder decoder = new Decoder.Default();
        private ErrorDecoder errorDecoder = new ErrorDecoder.Default();
        private Request.Options options = new Request.Options();
        private InvocationHandlerFactory invocationHandlerFactory = new Default();
        private Object loadBalancerKey = null;
        private final List<HeaderSupplier> headerSuppliers = Lists.newArrayList();

        @Data
        @Accessors(fluent = true)
        public static class BuiltData {
            private final List<RequestInterceptor> requestInterceptors;
            private final Logger.Level logLevel;
            private final Contract contract;
            private final Client client;
            private final Retryer retryer;
            private final Logger logger;
            private final Encoder encoder;
            private final Decoder decoder;
            private final ErrorDecoder errorDecoder;
            private final Request.Options options;
            private final InvocationHandlerFactory invocationHandlerFactory;
            private final Object loadBalancerKey;
            private final List<HeaderSupplier> headerSuppliers;
        }

        /**
         * Adds a single request interceptor to the builder.
         */
        public Builder requestInterceptor(RequestInterceptor requestInterceptor) {
            this.requestInterceptors.add(requestInterceptor);
            return this;
        }

        /**
         * Sets the full set of request interceptors for the builder, overwriting any previous
         * interceptors.
         */
        public Builder requestInterceptors(Iterable<RequestInterceptor> requestInterceptors) {
            this.requestInterceptors.clear();
            for (RequestInterceptor requestInterceptor : requestInterceptors) {
                this.requestInterceptors.add(requestInterceptor);
            }
            return this;
        }

        /**
         * Adds a single request interceptor to the builder.
         */
        public Builder headerSupplier(HeaderSupplier headerSupplier) {
            this.headerSuppliers.add(headerSupplier);
            return this;
        }

        /**
         * Sets the full set of header suppliers for the builder, overwriting any previous
         * suppliers.
         */
        public Builder headerSuppliers(Iterable<HeaderSupplier> headerSuppliers) {
            this.headerSuppliers.clear();
            for (HeaderSupplier item : headerSuppliers) {
                this.headerSuppliers.add(item);
            }
            return this;
        }

        public <T> FeignClient<T> target(Class<T> apiType, String url) {
            return target(new Target.HardCodedTarget<T>(apiType, url));
        }

        public <T> FeignClient<T> target(Target<T> target) {
            return build().newInstance(target);
        }

        public FeignMaker build() {
            return new ReflectiveFeignMaker(
                    new BuiltData(
                        requestInterceptors,
                        logLevel,
                        contract,
                        client,
                        retryer,
                        logger,
                        encoder,
                        decoder,
                        errorDecoder,
                        options,
                        invocationHandlerFactory,
                        loadBalancerKey,
                        headerSuppliers
                    )
            );
        }
    }
}
