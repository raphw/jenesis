package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;

class JenesisClassLoaderBridge {

    private final ClassLoader loader;

    private final Class<?> foreignBuildExecutor;
    private final Class<?> foreignBuildExecutorModule;

    private final MethodHandle foreignAccept;
    private final MethodHandle foreignApply;
    private final MethodHandle foreignShouldRun;

    private final MethodHandle foreignContextCtor;
    private final MethodHandle foreignArgumentCtor;
    private final MethodHandle foreignResultNext;

    private final Map<String, Object> foreignChecksumValues;

    JenesisClassLoaderBridge(ClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        foreignBuildExecutor = Class.forName(BuildExecutor.class.getName(), false, loader);
        foreignBuildExecutorModule = Class.forName(BuildExecutorModule.class.getName(), false, loader);
        Class<?> foreignBuildStep = Class.forName(BuildStep.class.getName(), false, loader);
        Class<?> foreignBuildStepContext = Class.forName(BuildStepContext.class.getName(), false, loader);
        Class<?> foreignBuildStepArgument = Class.forName(BuildStepArgument.class.getName(), false, loader);
        Class<?> foreignBuildStepResult = Class.forName(BuildStepResult.class.getName(), false, loader);
        Class<?> foreignChecksumStatus = Class.forName(ChecksumStatus.class.getName(), false, loader);

        MethodHandles.Lookup lookup = MethodHandles.lookup();
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

        Map<String, Object> values = new HashMap<>();
        for (ChecksumStatus status : ChecksumStatus.values()) {
            Field field = foreignChecksumStatus.getField(status.name());
            values.put(status.name(), field.get(null));
        }
        foreignChecksumValues = Map.copyOf(values);
    }

    Object findProvider(URI mainArtifact) {
        return ServiceLoader.load(foreignBuildExecutorModule, loader)
                .stream()
                .filter(provider -> URI.create(provider.type()
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toString()).equals(mainArtifact))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No BuildExecutorModule service provider found for " + mainArtifact))
                .get();
    }

    void accept(Object foreignModule, BuildExecutor hostExecutor, SequencedMap<String, Path> inherited) throws IOException {
        Object foreignProxy = java.lang.reflect.Proxy.newProxyInstance(
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
            value.files().forEach((path, status) ->
                    foreignFiles.put(path, foreignChecksumValues.get(status.name())));
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
        return new BuildStep() {
            @Override
            public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
                try {
                    return (boolean) foreignShouldRun.invoke(foreignStep, toForeignArguments(arguments));
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
                    CompletionStage<Object> foreign = (CompletionStage<Object>) foreignApply.invoke(
                            foreignStep, executor, toForeignContext(context), toForeignArguments(arguments));
                    return foreign.thenApply(JenesisClassLoaderBridge.this::fromForeignResult);
                } catch (IOException | RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
        };
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
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
    }
}
