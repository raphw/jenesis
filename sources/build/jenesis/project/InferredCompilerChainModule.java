package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.PathPlacement;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Javac;
import build.jenesis.step.ProcessHandler;

public class InferredCompilerChainModule implements BuildExecutorModule {

    public static final String JAVAC = "javac", KOTLINC = "kotlinc", SCALAC = "scalac", GROOVYC = "groovyc", RESOURCE = "resource";
    public static final String COMPILE = "compile";
    private static final String SCAN = "scan";
    private static final String SCAN_FILE = "scan.properties";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final PathPlacement pathPlacement;

    public InferredCompilerChainModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, PathPlacement.INFERRED);
    }

    private InferredCompilerChainModule(Map<String, Repository> repositories,
                                        Map<String, Resolver> resolvers,
                                        Pinning pinning,
                                        PathPlacement pathPlacement) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.pathPlacement = pathPlacement;
    }

    public InferredCompilerChainModule pinning(Pinning pinning) {
        return new InferredCompilerChainModule(repositories, resolvers, pinning, pathPlacement);
    }

    public InferredCompilerChainModule pathPlacement(PathPlacement pathPlacement) {
        return new InferredCompilerChainModule(repositories, resolvers, pinning, pathPlacement);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(SCAN, new Scan(), inherited.sequencedKeySet());
        SequencedSet<String> compileInputs = new LinkedHashSet<>(inherited.sequencedKeySet());
        compileInputs.add(SCAN);
        buildExecutor.addModule(COMPILE,
                new Compile(repositories, resolvers, pinning, pathPlacement),
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
            boolean[] flags = new boolean[5];
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
                        } else if (name.endsWith(".groovy")) {
                            flags[3] = true;
                        } else {
                            flags[4] = true;
                        }
                        return flags[0] && flags[1] && flags[2] && flags[3] && flags[4]
                                ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }
                });
                if (flags[0] && flags[1] && flags[2] && flags[3] && flags[4]) {
                    break;
                }
            }
            SequencedProperties properties = new SequencedProperties();
            properties.setProperty(JAVAC, Boolean.toString(flags[0]));
            properties.setProperty(KOTLINC, Boolean.toString(flags[1]));
            properties.setProperty(SCALAC, Boolean.toString(flags[2]));
            properties.setProperty(GROOVYC, Boolean.toString(flags[3]));
            properties.setProperty(RESOURCE, Boolean.toString(flags[4]));
            properties.store(context.next().resolve(SCAN_FILE));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record Compile(Map<String, Repository> repositories,
                           Map<String, Resolver> resolvers,
                           Pinning pinning,
                           PathPlacement pathPlacement) implements BuildExecutorModule {

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
            Path scanFolder = inherited.get(PREVIOUS + SCAN);
            if (scanFolder == null) {
                throw new IllegalStateException("Compile sub-module is missing its upstream scan input");
            }
            SequencedProperties scan = SequencedProperties.ofFiles(scanFolder.resolve(SCAN_FILE));
            boolean hasJava = Boolean.parseBoolean(scan.getProperty(JAVAC));
            boolean hasKotlin = Boolean.parseBoolean(scan.getProperty(KOTLINC));
            boolean hasScala = Boolean.parseBoolean(scan.getProperty(SCALAC));
            boolean hasGroovy = Boolean.parseBoolean(scan.getProperty(GROOVYC));
            boolean hasResource = Boolean.parseBoolean(scan.getProperty(RESOURCE));

            SequencedSet<String> sourceInputs = new LinkedHashSet<>(inherited.sequencedKeySet());
            sourceInputs.remove(PREVIOUS + SCAN);

            SequencedSet<String> dependencies = new LinkedHashSet<>(sourceInputs);
            if (hasKotlin) {
                buildExecutor.addModule(KOTLINC,
                        new KotlinCompilerModule(repositories, resolvers)
                                .pinning(pinning)
                                .includeResources(!hasJava && !hasScala && !hasGroovy),
                        dependencies);
                SequencedSet<String> updated = new LinkedHashSet<>(dependencies);
                updated.add(KOTLINC + "/" + KotlinCompilerModule.CLASSES);
                dependencies = updated;
            }
            if (hasScala) {
                buildExecutor.addModule(SCALAC,
                        new ScalaCompilerModule(repositories, resolvers)
                                .pinning(pinning)
                                .includeResources(!hasJava && !hasKotlin && !hasGroovy),
                        dependencies);
                SequencedSet<String> updated = new LinkedHashSet<>(dependencies);
                updated.add(SCALAC + "/" + ScalaCompilerModule.CLASSES);
                dependencies = updated;
            }
            if (hasJava) {
                buildExecutor.addStep(JAVAC,
                        new Javac(ProcessHandler.Factory.of())
                                .includeResources(!hasKotlin && !hasScala && !hasGroovy)
                                .pathPlacement(pathPlacement),
                        dependencies);
                SequencedSet<String> updated = new LinkedHashSet<>(dependencies);
                updated.add(JAVAC);
                dependencies = updated;
            }
            if (hasGroovy) {
                buildExecutor.addModule(GROOVYC,
                        new GroovyCompilerModule(repositories, resolvers)
                                .pinning(pinning)
                                .includeResources(!hasJava && !hasKotlin && !hasScala),
                        dependencies);
            }
            int compilers = (hasJava ? 1 : 0) + (hasKotlin ? 1 : 0) + (hasScala ? 1 : 0) + (hasGroovy ? 1 : 0);
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
                        Path relative = sources.relativize(file);
                        if (!name.endsWith(".java")
                                && !name.endsWith(".kt")
                                && !name.endsWith(".scala")
                                && !name.endsWith(".groovy")
                                && !BuildStep.underMetaInfVersions(relative)) {
                            BuildStep.linkOrCopy(target.resolve(relative), file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
