package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Javac;

public class InferredCompilerChainModule implements BuildExecutorModule {

    public static final String JAVA = "java", KOTLIN = "kotlin", SCALA = "scala", RESOURCE = "resource";
    public static final String COMPILE = "compile";
    private static final String SCAN = "scan";
    private static final String SCAN_FILE = "scan.properties";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean process;
    private final boolean strictPinning;

    public InferredCompilerChainModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, false, false);
    }

    public InferredCompilerChainModule(Map<String, Repository> repositories,
                                       Map<String, Resolver> resolvers,
                                       boolean process) {
        this(repositories, resolvers, process, false);
    }

    private InferredCompilerChainModule(Map<String, Repository> repositories,
                                        Map<String, Resolver> resolvers,
                                        boolean process,
                                        boolean strictPinning) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.process = process;
        this.strictPinning = strictPinning;
    }

    public InferredCompilerChainModule strictPinning(boolean strictPinning) {
        return new InferredCompilerChainModule(repositories, resolvers, process, strictPinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(SCAN, new Scan(), inherited.sequencedKeySet());
        SequencedSet<String> compileInputs = new LinkedHashSet<>(inherited.sequencedKeySet());
        compileInputs.add(SCAN);
        buildExecutor.addModule(COMPILE,
                new Compile(repositories, resolvers, process, strictPinning),
                compileInputs);
    }

    @Override
    public Optional<String> resolve(String path) {
        return path.equals(SCAN) ? Optional.empty() : Optional.of(path);
    }

    private static class Scan implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            boolean[] flags = new boolean[4];
            for (BuildStepArgument argument : arguments.values()) {
                Path sources = argument.folder().resolve(BuildStep.SOURCES);
                if (!Files.exists(sources)) {
                    continue;
                }
                Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".java")) {
                            flags[0] = true;
                        } else if (name.endsWith(".kt")) {
                            flags[1] = true;
                        } else if (name.endsWith(".scala")) {
                            flags[2] = true;
                        } else {
                            flags[3] = true;
                        }
                        return flags[0] && flags[1] && flags[2] && flags[3]
                                ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }
                });
                if (flags[0] && flags[1] && flags[2] && flags[3]) {
                    break;
                }
            }
            SequencedProperties properties = new SequencedProperties();
            properties.setProperty(JAVA, Boolean.toString(flags[0]));
            properties.setProperty(KOTLIN, Boolean.toString(flags[1]));
            properties.setProperty(SCALA, Boolean.toString(flags[2]));
            properties.setProperty(RESOURCE, Boolean.toString(flags[3]));
            properties.store(context.next().resolve(SCAN_FILE));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record Compile(Map<String, Repository> repositories,
                           Map<String, Resolver> resolvers,
                           boolean process,
                           boolean strictPinning) implements BuildExecutorModule {

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
            Path scanFolder = inherited.get(PREVIOUS + SCAN);
            if (scanFolder == null) {
                throw new IllegalStateException("Compile sub-module is missing its upstream scan input");
            }
            SequencedProperties scan = SequencedProperties.ofFiles(scanFolder.resolve(SCAN_FILE));
            boolean hasJava = Boolean.parseBoolean(scan.getProperty(JAVA));
            boolean hasKotlin = Boolean.parseBoolean(scan.getProperty(KOTLIN));
            boolean hasScala = Boolean.parseBoolean(scan.getProperty(SCALA));
            boolean hasResource = Boolean.parseBoolean(scan.getProperty(RESOURCE));

            SequencedSet<String> sourceInputs = new LinkedHashSet<>(inherited.sequencedKeySet());
            sourceInputs.remove(PREVIOUS + SCAN);

            SequencedSet<String> dependencies = new LinkedHashSet<>(sourceInputs);
            if (hasJava) {
                buildExecutor.addStep(JAVA,
                        (process ? Javac.process() : Javac.tool()).includeResources(!hasKotlin && !hasScala),
                        dependencies);
                SequencedSet<String> updated = new LinkedHashSet<>(sourceInputs);
                updated.add(JAVA);
                dependencies = updated;
            }
            if (hasKotlin) {
                buildExecutor.addModule(KOTLIN,
                        new KotlinCompilerModule(repositories, resolvers)
                                .strictPinning(strictPinning)
                                .includeResources(!hasJava && !hasScala),
                        dependencies);
                SequencedSet<String> updated = new LinkedHashSet<>(dependencies);
                updated.add(KOTLIN);
                dependencies = updated;
            }
            if (hasScala) {
                buildExecutor.addModule(SCALA,
                        new ScalaCompilerModule(repositories, resolvers)
                                .strictPinning(strictPinning)
                                .includeResources(!hasJava && !hasKotlin),
                        dependencies);
            }
            int compilers = (hasJava ? 1 : 0) + (hasKotlin ? 1 : 0) + (hasScala ? 1 : 0);
            if (hasResource && compilers != 1) {
                buildExecutor.addStep(RESOURCE, new Resources(), sourceInputs);
            }
        }
    }

    private static class Resources implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path target = Files.createDirectory(context.next().resolve(BuildStep.CLASSES));
            for (BuildStepArgument argument : arguments.values()) {
                Path sources = argument.folder().resolve(BuildStep.SOURCES);
                if (!Files.exists(sources)) {
                    continue;
                }
                Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(target.resolve(sources.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".java") && !name.endsWith(".kt") && !name.endsWith(".scala")) {
                            BuildStep.linkOrCopy(target.resolve(sources.relativize(file)), file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
