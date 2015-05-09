package feign;

import feign.codec.EncodeException;
import feign.codec.Encoder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

public class ReflectiveFeignMaker extends FeignMaker {

    private final Builder.BuiltData data;

    ReflectiveFeignMaker(Builder.BuiltData data) {
        this.data = data;
    }

    /**
     * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
     * to cache the result.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> FeignClient<T> newInstance(Target<T> target) {
        return new FeignClient(target, data);

    }

    static class FeignMakerInvocationHandler implements InvocationHandler {

        private final Target target;
        private final Map<Method, InvocationHandlerFactory.MethodHandler> dispatch;

        FeignMakerInvocationHandler(Target target, Map<Method, InvocationHandlerFactory.MethodHandler> dispatch) {
            this.target = checkNotNull(target, "target");
            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("equals".equals(method.getName())) {
                try {
                    Object
                            otherHandler =
                            args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
                    return equals(otherHandler);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else if ("hashCode".equals(method.getName())) {
                return hashCode();
            } else if ("toString".equals(method.getName())) {
                return toString();
            }
            return dispatch.get(method).invoke(args);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof FeignMakerInvocationHandler) {
                FeignMakerInvocationHandler other = (FeignMakerInvocationHandler) obj;
                return target.equals(other.target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return target.hashCode();
        }

        @Override
        public String toString() {
            return target.toString();
        }
    }

    static final class ParseHandlersByName {

        private final Encoder encoder;

        ParseHandlersByName(Encoder encoder) {
            this.encoder = checkNotNull(encoder, "encoder");
        }

        public Map<String, BuildTemplateByResolvingArgs> apply(Target key, List<MethodMetadata> metadata) {

            Map<String, BuildTemplateByResolvingArgs> result = new LinkedHashMap<String, BuildTemplateByResolvingArgs>();
            for (MethodMetadata md : metadata) {
                BuildTemplateByResolvingArgs buildTemplate = null;
                if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
                    buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder);
                } else if (md.bodyIndex() != null) {
                    buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder);
                } else {
                    buildTemplate = new BuildTemplateByResolvingArgs(md);
                }
                result.put(md.configKey(), buildTemplate);
            }

            return result;
        }
    }

    public static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

        protected final MethodMetadata metadata;
        private final Map<Integer, Param.Expander> indexToExpander = new LinkedHashMap<Integer, Param.Expander>();

        private BuildTemplateByResolvingArgs(MethodMetadata metadata) {
            this.metadata = metadata;
            if (metadata.indexToExpanderClass().isEmpty()) {
                return;
            }
            for (Map.Entry<Integer, Class<? extends Param.Expander>> indexToExpanderClass : metadata
                    .indexToExpanderClass().entrySet()) {
                try {
                    indexToExpander
                            .put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
                } catch (InstantiationException e) {
                    throw new IllegalStateException(e);
                } catch ( IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public RequestTemplate create(Object[] argv) {
            RequestTemplate mutable = new RequestTemplate(metadata.template());
            if (metadata.urlIndex() != null) {
                int urlIndex = metadata.urlIndex();
                checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
                mutable.insert(0, String.valueOf(argv[urlIndex]));
            }
            Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
            for (Map.Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
                int i = entry.getKey();
                Object value = argv[entry.getKey()];
                if (value != null) { // Null values are skipped.
                    if (indexToExpander.containsKey(i)) {
                        value = indexToExpander.get(i).expand(value);
                    }
                    for (String name : entry.getValue()) {
                        varBuilder.put(name, value);
                    }
                }
            }
            return resolve(argv, mutable, varBuilder);
        }

        protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable,
                                          Map<String, Object> variables) {
            return mutable.resolve(variables);
        }
    }

    private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder) {
            super(metadata);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                if (metadata.formParams().contains(entry.getKey())) {
                    formVariables.put(entry.getKey(), entry.getValue());
                }
            }
            try {
                encoder.encode(formVariables, Types.MAP_STRING_WILDCARD, mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }

    private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder) {
            super(metadata);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Object body = argv[metadata.bodyIndex()];
            checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
            try {
                encoder.encode(body, metadata.bodyType(), mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }
}
//
//public class ReflectiveFeignMaker extends FeignMaker {
//
//    private final ParseHandlersByName targetToHandlersByName;
//    private final InvocationHandlerFactory factory;
//
//    @Inject
//    ReflectiveFeignMaker(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory) {
//        this.targetToHandlersByName = targetToHandlersByName;
//        this.factory = factory;
//    }
//
//    /**
//     * creates an api binding to the {@code target}. As this invokes reflection,
//     * care should be taken to cache the result.
//     */
//    @SuppressWarnings("unchecked") @Override public <T> FeignClient<T> newInstance(Target target) {
//        Map<String, InvocationHandlerFactory.MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
//        Map<Method, InvocationHandlerFactory.MethodHandler> methodToHandler = new LinkedHashMap<Method, InvocationHandlerFactory.MethodHandler>();
//        for (Method method : target.type().getDeclaredMethods()) {
//            if (method.getDeclaringClass() == Object.class)
//                continue;
//            methodToHandler.put(method, nameToHandler.get(FeignMaker.configKey(method)));
//        }
//
//        return new FeignClient(target, methodToHandler, factory);
//    }
//
//    static class FeignMakerInvocationHandler implements InvocationHandler {
//
//        private final Target target;
//        private final Map<Method, InvocationHandlerFactory.MethodHandler> dispatch;
//
//        FeignMakerInvocationHandler(Target target, Map<Method, InvocationHandlerFactory.MethodHandler> dispatch) {
//            this.target = checkNotNull(target, "target");
//            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
//        }
//
//        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
//            if ("equals".equals(method.getName())) {
//                try {
//                    Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
//                    return equals(otherHandler);
//                } catch (IllegalArgumentException e) {
//                    return false;
//                }
//            } else if ("hashCode".equals(method.getName())) {
//                return hashCode();
//            } else if ("toString".equals(method.getName())) {
//                return toString();
//            }
//            return dispatch.get(method).invoke(args);
//        }
//
//        @Override public boolean equals(Object obj) {
//            if (obj instanceof FeignMakerInvocationHandler) {
//                FeignMakerInvocationHandler other = (FeignMakerInvocationHandler) obj;
//                return target.equals(other.target);
//            }
//            return false;
//        }
//
//        @Override public int hashCode() {
//            return target.hashCode();
//        }
//
//        @Override public String toString() {
//            return target.toString();
//        }
//    }
//
//    @dagger.Module(complete = false, injects = {FeignMaker.class, SynchronousMethodHandler.Factory.class}, library = true)
//    public static class Module {
//        @Provides(type = Provides.Type.SET_VALUES)
//        Set<RequestInterceptor> noRequestInterceptors() {
//            return Collections.emptySet();
//        }
//
//        @Provides FeignMaker provideFeign(ReflectiveFeignMaker in) {
//            return in;
//        }
//    }
//
//    static final class ParseHandlersByName {
//        private final Contract contract;
//        private final Request.Options options;
//        private final Encoder encoder;
//        private final Decoder decoder;
//        private final ErrorDecoder errorDecoder;
//        private final SynchronousMethodHandler.Factory factory;
//
//        @SuppressWarnings("unchecked")
//        @Inject ParseHandlersByName(Contract contract, Request.Options options, Encoder encoder, Decoder decoder,
//                                    ErrorDecoder errorDecoder, SynchronousMethodHandler.Factory factory) {
//            this.contract = contract;
//            this.options = options;
//            this.factory = factory;
//            this.errorDecoder = errorDecoder;
//            this.encoder = checkNotNull(encoder, "encoder");
//            this.decoder = checkNotNull(decoder, "decoder");
//        }
//
//        public <T> Map<String, InvocationHandlerFactory.MethodHandler> apply(Target key) {
//            List<MethodMetadata> metadata = contract.parseAndValidatateMetadata(key.type());
//            Map<String, InvocationHandlerFactory.MethodHandler> result = new LinkedHashMap<String, InvocationHandlerFactory.MethodHandler>();
//            for (MethodMetadata md : metadata) {
//                BuildTemplateByResolvingArgs buildTemplate;
//                if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
//                    buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder);
//                } else if (md.bodyIndex() != null) {
//                    buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder);
//                } else {
//                    buildTemplate = new BuildTemplateByResolvingArgs(md);
//                }
//                result.put(md.configKey(), factory.create(key, md, buildTemplate, options, decoder, errorDecoder));
//            }
//            return result;
//        }
//    }
//
//    private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {
//        protected final MethodMetadata metadata;
//        private final Map<Integer, Param.Expander> indexToExpander = new LinkedHashMap<Integer, Param.Expander>();
//
//        private BuildTemplateByResolvingArgs(MethodMetadata metadata) {
//            this.metadata = metadata;
//            if (metadata.indexToExpanderClass().isEmpty()) return;
//            for (Map.Entry<Integer, Class<? extends Param.Expander>> indexToExpanderClass : metadata.indexToExpanderClass().entrySet()) {
//                try {
//                    indexToExpander.put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
//                } catch (InstantiationException | IllegalAccessException e) {
//                    throw new IllegalStateException(e);
//                }
//            }
//        }
//
//        @Override public RequestTemplate create(Object[] argv) {
//            RequestTemplate mutable = new RequestTemplate(metadata.template());
//            if (metadata.urlIndex() != null) {
//                int urlIndex = metadata.urlIndex();
//                checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
//                mutable.insert(0, String.valueOf(argv[urlIndex]));
//            }
//            Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
//            for (Map.Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
//                int i = entry.getKey();
//                Object value = argv[entry.getKey()];
//                if (value != null) { // Null values are skipped.
//                    if (indexToExpander.containsKey(i)) {
//                        value = indexToExpander.get(i).expand(value);
//                    }
//                    for (String name : entry.getValue())
//                        varBuilder.put(name, value);
//                }
//            }
//            return resolve(argv, mutable, varBuilder);
//        }
//
//        protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
//            return mutable.resolve(variables);
//        }
//    }
//
//    private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {
//        private final Encoder encoder;
//
//        private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder) {
//            super(metadata);
//            this.encoder = encoder;
//        }
//
//        @Override
//        protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
//            Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
//            for (Map.Entry<String, Object> entry : variables.entrySet()) {
//                if (metadata.formParams().contains(entry.getKey()))
//                    formVariables.put(entry.getKey(), entry.getValue());
//            }
//            try {
//                encoder.encode(formVariables, mutable);
//            } catch (EncodeException e) {
//                throw e;
//            } catch (RuntimeException e) {
//                throw new EncodeException(e.getMessage(), e);
//            }
//            return super.resolve(argv, mutable, variables);
//        }
//    }
//
//    private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {
//        private final Encoder encoder;
//
//        private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder) {
//            super(metadata);
//            this.encoder = encoder;
//        }
//
//        @Override
//        protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
//            Object body = argv[metadata.bodyIndex()];
//            checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
//            try {
//                encoder.encode(body, mutable);
//            } catch (EncodeException e) {
//                throw e;
//            } catch (RuntimeException e) {
//                throw new EncodeException(e.getMessage(), e);
//            }
//            return super.resolve(argv, mutable, variables);
//        }
//    }
//}
