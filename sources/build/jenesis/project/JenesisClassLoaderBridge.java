package build.jenesis.project;

import module java.base;
import java.lang.annotation.Annotation;
import java.lang.module.Configuration;
import java.lang.reflect.Proxy;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildModuleName;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;

class JenesisClassLoaderBridge implements AutoCloseable {

    private ModuleLayer layer;
    private ClassLoader loader;

    private Class<?> foreignBuildExecutorModule;

    private MethodHandle foreignAccept;
    private MethodHandle foreignApply;
    private MethodHandle foreignShouldRun;

    private MethodHandle foreignContextCtor;
    private MethodHandle foreignArgumentCtor;
    private MethodHandle foreignResultNext;

    private Class<?> foreignBuildExecutor;

    private Class<? extends Annotation> foreignBuildModuleName;
    private MethodHandle foreignBuildModuleNameValue;

    private Map<String, Object> foreignChecksumValues;

    JenesisClassLoaderBridge(Collection<Path> artifacts) throws ReflectiveOperationException {
        ModuleFinder finder = ModuleFinder.of(artifacts.toArray(Path[]::new));
        Set<String> roots = finder.findAll().stream()
                .map(ref -> ref.descriptor().name())
                .collect(Collectors.toUnmodifiableSet());
        if (roots.isEmpty()) {
            throw new IllegalStateException("No modules found in " + artifacts);
        }
        Configuration config = ModuleLayer.boot().configuration()
                .resolveAndBind(finder, ModuleFinder.of(), roots);
        ModuleLayer.Controller controller = ModuleLayer.defineModulesWithOneLoader(
                config, List.of(ModuleLayer.boot()), ClassLoader.getPlatformClassLoader());
        layer = controller.layer();
        loader = layer.findLoader(roots.iterator().next());
        foreignBuildExecutor = Class.forName(BuildExecutor.class.getName(), false, loader);
        foreignBuildExecutorModule = Class.forName(BuildExecutorModule.class.getName(), false, loader);
        Module hostModule = JenesisClassLoaderBridge.class.getModule();
        Module foreignJenesis = foreignBuildExecutor.getModule();
        for (Module foreignModule : layer.modules()) {
            hostModule.addReads(foreignModule);
        }
        controller.addOpens(foreignJenesis, BuildExecutor.class.getPackageName(), hostModule);
        Class<?> foreignBuildStep = Class.forName(BuildStep.class.getName(), false, loader);
        Class<?> foreignBuildStepContext = Class.forName(BuildStepContext.class.getName(), false, loader);
        Class<?> foreignBuildStepArgument = Class.forName(BuildStepArgument.class.getName(), false, loader);
        Class<?> foreignBuildStepResult = Class.forName(BuildStepResult.class.getName(), false, loader);
        Class<?> foreignChecksumStatus = Class.forName(ChecksumStatus.class.getName(), false, loader);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(foreignBuildExecutorModule, MethodHandles.lookup());
        foreignAccept = lookup.findVirtual(foreignBuildExecutorModule, "accept",
                MethodType.methodType(void.class, foreignBuildExecutor, SequencedMap.class));
        foreignApply = lookup.findVirtual(foreignBuildStep, "apply",
                MethodType.methodType(CompletionStage.class, Executor.class, foreignBuildStepContext, SequencedMap.class));
        foreignShouldRun = lookup.findVirtual(foreignBuildStep, "shouldRun",
                MethodType.methodType(boolean.class, SequencedMap.class));
        foreignContextCtor = lookup.findConstructor(foreignBuildStepContext,
                MethodType.methodType(void.class, Path.class, Path.class, Path.class));
        foreignArgumentCtor = lookup.findConstructor(foreignBuildStepArgument,
                MethodType.methodType(void.class, Path.class, Map.class));
        foreignResultNext = lookup.findVirtual(foreignBuildStepResult, "next",
                MethodType.methodType(boolean.class));
        foreignBuildModuleName = Class.forName(BuildModuleName.class.getName(), false, loader)
                .asSubclass(Annotation.class);
        foreignBuildModuleNameValue = lookup.findVirtual(foreignBuildModuleName, "value",
                MethodType.methodType(String.class));
        Map<String, Object> values = new HashMap<>();
        for (ChecksumStatus status : ChecksumStatus.values()) {
            Field field = foreignChecksumStatus.getField(status.name());
            values.put(status.name(), field.get(null));
        }
        foreignChecksumValues = Map.copyOf(values);
    }

    @Override
    public void close() {
        layer = null;
        loader = null;
        foreignBuildExecutorModule = null;
        foreignAccept = null;
        foreignApply = null;
        foreignShouldRun = null;
        foreignContextCtor = null;
        foreignArgumentCtor = null;
        foreignResultNext = null;
        foreignBuildExecutor = null;
        foreignBuildModuleName = null;
        foreignBuildModuleNameValue = null;
        foreignChecksumValues = null;
    }

    Object findProvider(String name) {
        if (loader == null) {
            throw new IllegalStateException("Build module class loader bridge was already closed");
        }
        Object match = null;
        for (Object provider : ServiceLoader.load(layer, foreignBuildExecutorModule)) {
            Annotation annotation = provider.getClass().getAnnotation(foreignBuildModuleName);
            String providerName;
            if (annotation == null) {
                providerName = null;
            } else {
                try {
                    providerName = (String) foreignBuildModuleNameValue.invoke(annotation);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
            if (!Objects.equals(name, providerName)) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException(name == null
                        ? "Multiple unnamed BuildExecutorModule service providers found in layer"
                        : "Multiple BuildExecutorModule service providers named " + name + " found in layer");
            }
            match = provider;
        }
        if (match == null) {
            throw new IllegalStateException(name == null
                    ? "No unnamed BuildExecutorModule service provider found in layer"
                    : "No BuildExecutorModule service provider named " + name + " found in layer");
        }
        return match;
    }

    void accept(Object foreignModule, BuildExecutor hostExecutor, SequencedMap<String, Path> inherited) throws IOException {
        Object foreignProxy = Proxy.newProxyInstance(
                loader,
                new Class<?>[]{foreignBuildExecutor},
                new BuildExecutorBridge(hostExecutor));
        try {
            foreignAccept.invoke(foreignModule, foreignProxy, inherited);
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private Object toForeignContext(BuildStepContext context) {
        try {
            return foreignContextCtor.invoke(context.previous(), context.next(), context.supplement());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private SequencedMap<String, Object> toForeignArguments(SequencedMap<String, BuildStepArgument> arguments) {
        SequencedMap<String, Object> foreign = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            Map<Path, Object> foreignFiles = new LinkedHashMap<>();
            value.files().forEach((path, checksum) ->
                    foreignFiles.put(path, foreignChecksumValues.get(checksum.status().name())));
            try {
                foreign.put(key, foreignArgumentCtor.invoke(value.folder(), foreignFiles));
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });
        return foreign;
    }

    private BuildStepResult fromForeignResult(Object foreignResult) {
        try {
            return new BuildStepResult((boolean) foreignResultNext.invoke(foreignResult));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private BuildStep wrapStep(Object foreignStep) {
        return new ForeignBuildStep(this, foreignStep);
    }

    private static final class ForeignBuildStep implements BuildStep {

        private final transient JenesisClassLoaderBridge bridge;
        private final Object foreignStep;

        ForeignBuildStep(JenesisClassLoaderBridge bridge, Object foreignStep) {
            this.bridge = bridge;
            this.foreignStep = foreignStep;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            try {
                return (boolean) bridge.foreignShouldRun.invoke(foreignStep, bridge.toForeignArguments(arguments));
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            try {
                CompletionStage<Object> foreign = (CompletionStage<Object>) bridge.foreignApply.invoke(
                        foreignStep, executor, bridge.toForeignContext(context), bridge.toForeignArguments(arguments));
                return foreign.thenApply(bridge::fromForeignResult);
            } catch (IOException | RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    private BuildExecutorModule wrapModule(Object foreignModule) {
        return (hostExecutor, inherited) -> accept(foreignModule, hostExecutor, inherited);
    }

    private class BuildExecutorBridge implements InvocationHandler {

        private final BuildExecutor host;

        BuildExecutorBridge(BuildExecutor host) {
            this.host = host;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return switch (method.getName()) {
                case "addSource" -> {
                    if (method.getParameterCount() == 2) {
                        host.addSource((String) args[0], (Path) args[1]);
                    } else {
                        host.addSource((String) args[0], wrapStep(args[1]), (SequencedSet<Path>) args[2]);
                    }
                    yield null;
                }
                case "replaceSource" -> {
                    if (method.getParameterCount() == 2) {
                        host.replaceSource((String) args[0], (Path) args[1]);
                    } else {
                        host.replaceSource((String) args[0], wrapStep(args[1]), (SequencedSet<Path>) args[2]);
                    }
                    yield null;
                }
                case "addStep" -> {
                    host.addStep((String) args[0], wrapStep(args[1]), (SequencedMap<String, String>) args[2]);
                    yield null;
                }
                case "replaceStep" -> {
                    host.replaceStep((String) args[0], wrapStep(args[1]));
                    yield null;
                }
                case "prependStep" -> {
                    host.prependStep((String) args[0], (String) args[1], wrapStep(args[2]));
                    yield null;
                }
                case "appendStep" -> {
                    host.appendStep((String) args[0], (String) args[1], wrapStep(args[2]));
                    yield null;
                }
                case "addModule" -> {
                    host.addModule((String) args[0],
                            wrapModule(args[1]),
                            (Function<String, Optional<String>>) args[2],
                            (SequencedMap<String, String>) args[3]);
                    yield null;
                }
                case "replaceModule" -> {
                    host.replaceModule((String) args[0],
                            wrapModule(args[1]),
                            (Function<String, Optional<String>>) args[2]);
                    yield null;
                }
                case "prependModule" -> {
                    host.prependModule((String) args[0],
                            (String) args[1],
                            wrapModule(args[2]),
                            (Function<String, Optional<String>>) args[3]);
                    yield null;
                }
                case "appendModule" -> {
                    host.appendModule((String) args[0],
                            (String) args[1],
                            wrapModule(args[2]),
                            (Function<String, Optional<String>>) args[3]);
                    yield null;
                }
                case "execute" -> host.execute((Executor) args[0], (String[]) args[1]);
                default -> throw new UnsupportedOperationException(
                        "This build module calls BuildExecutor." + method.getName()
                                + ", which the running version of Jenesis does not provide. The module was "
                                + "built against a newer Jenesis API; upgrade Jenesis to a version that "
                                + "supports it (" + method + ").");
            };
        }
    }
}
