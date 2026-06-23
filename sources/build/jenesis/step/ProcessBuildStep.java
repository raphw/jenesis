package build.jenesis.step;

import module java.base;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public abstract class ProcessBuildStep implements BuildStep {

    public static final String PROCESS = "process/";

    static {
        if (System.getProperty("java.home") == null) {
            String home = System.getenv("JAVA_HOME");
            if (home == null) {
                throw new IllegalStateException("Neither java.home or JAVA_HOME available");
            }
            System.setProperty("java.home", home);
        }
    }

    protected final transient Function<List<String>, ? extends ProcessHandler> factory;
    private final String command;

    protected ProcessBuildStep(String command, Function<List<String>, ? extends ProcessHandler> factory) {
        this.command = command;
        this.factory = factory;
    }

    protected abstract CompletionStage<List<String>> process(Executor executor,
                                                             BuildStepContext context,
                                                             SequencedMap<String, BuildStepArgument> arguments,
                                                             SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException;

    public boolean acceptableExitCode(int code,
                                      Executor executor,
                                      BuildStepContext context,
                                      SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        return code == 0;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, SequencedMap<String, String>> properties = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            SequencedMap<String, String> folderMap = new LinkedHashMap<>();
            Path file = entry.getValue().folder().resolve(PROCESS + command + ".properties");
            if (Files.exists(file)) {
                SequencedProperties loaded = SequencedProperties.ofFiles(file);
                for (String key : loaded.stringPropertyNames()) {
                    folderMap.put(key, loaded.getProperty(key));
                }
            }
            properties.put(entry.getKey(), folderMap);
        }
        AtomicReference<Thread> worker = new AtomicReference<>();
        CompletableFuture<BuildStepResult> result = process(executor, context, arguments, properties).thenComposeAsync(processed -> {
            if (processed == null) {
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }
            CompletableFuture<BuildStepResult> future = new CompletableFuture<>();
            try {
                List<String> prepended = new ArrayList<>();
                for (SequencedMap<String, String> folderMap : properties.values()) {
                    for (Map.Entry<String, String> entry : folderMap.entrySet()) {
                        for (String value : entry.getValue().split("\n")) {
                            prepended.add(entry.getKey());
                            if (!value.isEmpty()) {
                                prepended.add(value);
                            }
                        }
                    }
                }
                Path output = context.supplement().resolve("output"), error = context.supplement().resolve("error");
                ProcessHandler handler = factory.apply(Stream.concat(prepended.stream(), processed.stream()).toList());
                Files.writeString(context.supplement().resolve("command"), String.join(" ", handler.commands()));
                if (Boolean.getBoolean("jenesis.print.command")) {
                    System.out.printf("%s%-11s%s %s%n",
                        BuildExecutorCallback.YELLOW,
                        "[EXECUTED]",
                        BuildExecutorCallback.RESET,
                        String.join(" ", handler.commands()));
                }
                executor.execute(() -> {
                    worker.set(Thread.currentThread());
                    try {
                        int exitCode = handler.execute(output, error);
                        if (acceptableExitCode(exitCode, executor, context, arguments)) {
                            future.complete(new BuildStepResult(true));
                        } else {
                            String outputString = Files.exists(output) ? Files.readString(output) : "";
                            String errorString = Files.exists(error) ? Files.readString(error) : "";
                            throw new IllegalStateException("Unexpected exit code: " + exitCode + "\n"
                                    + "To reproduce, execute:\n " + String.join(" ", handler.commands())
                                    + (outputString.isBlank() ? "" : ("\n\nOutput:\n" + outputString))
                                    + (errorString.isBlank() ? "" : ("\n\nError:\n" + errorString)));
                        }
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    } finally {
                        worker.set(null);
                    }
                });
                return future;
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return future;
        }).toCompletableFuture();
        result.whenComplete((_, throwable) -> {
            if (throwable != null) {
                Thread running = worker.get();
                if (running != null && running != Thread.currentThread()) {
                    running.interrupt();
                }
            }
        });
        return result;
    }
}
