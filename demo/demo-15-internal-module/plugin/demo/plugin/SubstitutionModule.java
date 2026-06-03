package demo.plugin;

import module java.base;
import module build.jenesis;

import org.json.JSONObject;

/**
 * A build module that Jenesis loads as a plugin with {@code InternalModule}. It
 * preprocesses the host project's Java sources: every {@code ${key}} placeholder
 * is replaced with the matching value from a substitution map. The map is parsed
 * with {@code org.json}, the external dependency this build module declares, so
 * the plugin demonstrates a loaded build module pulling in and using a real
 * third-party library at build time.
 */
public class SubstitutionModule implements BuildExecutorModule {

    @Override
    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
        executor.addStep("substitute", new Substitute(), inherited.sequencedKeySet().stream());
    }

    private record Substitute() implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            JSONObject substitutions = new JSONObject("""
                    {
                        "greeting": "Hello from a source preprocessed by an internal build module, using the org.json dependency!"
                    }
                    """);
            Path target = context.next().resolve(BuildStep.SOURCES);
            for (BuildStepArgument argument : arguments.values()) {
                Path sources = argument.folder().resolve(BuildStep.SOURCES);
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                Files.walkFileTree(sources, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                            throws IOException {
                        Files.createDirectories(target.resolve(sources.relativize(directory)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Path destination = target.resolve(sources.relativize(file));
                        if (file.toString().endsWith(".java")) {
                            String content = Files.readString(file);
                            for (String key : substitutions.keySet()) {
                                content = content.replace("${" + key + "}", substitutions.getString(key));
                            }
                            Files.writeString(destination, content);
                        } else {
                            BuildStep.linkOrCopy(destination, file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
