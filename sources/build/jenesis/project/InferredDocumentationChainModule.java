package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Javadoc;

public class InferredDocumentationChainModule implements BuildExecutorModule {

    public static final String JAVADOC = "javadoc", GROOVYDOC = "groovydoc", SCALADOC = "scaladoc", DOKKA = "dokka";
    public static final String DOCUMENT = "document";
    private static final String SCAN = "scan";
    private static final String SCAN_FILE = "scan.properties";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean process;
    private final Pinning pinning;

    public InferredDocumentationChainModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, false, null);
    }

    private InferredDocumentationChainModule(Map<String, Repository> repositories,
                                             Map<String, Resolver> resolvers,
                                             boolean process,
                                             Pinning pinning) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.process = process;
        this.pinning = pinning;
    }

    public InferredDocumentationChainModule process(boolean process) {
        return new InferredDocumentationChainModule(repositories, resolvers, process, pinning);
    }

    public InferredDocumentationChainModule pinning(Pinning pinning) {
        return new InferredDocumentationChainModule(repositories, resolvers, process, pinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(SCAN, new Scan(), inherited.sequencedKeySet());
        SequencedSet<String> documentInputs = new LinkedHashSet<>(inherited.sequencedKeySet());
        documentInputs.add(SCAN);
        buildExecutor.addModule(DOCUMENT,
                new Document(repositories, resolvers, process, pinning),
                documentInputs);
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
                        } else if (name.endsWith(".groovy")) {
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
            properties.setProperty(JAVADOC, Boolean.toString(flags[0]));
            properties.setProperty(DOKKA, Boolean.toString(flags[1]));
            properties.setProperty(SCALADOC, Boolean.toString(flags[2]));
            properties.setProperty(GROOVYDOC, Boolean.toString(flags[3]));
            properties.store(context.next().resolve(SCAN_FILE));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record Document(Map<String, Repository> repositories,
                            Map<String, Resolver> resolvers,
                            boolean process,
                            Pinning pinning) implements BuildExecutorModule {

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
            Path scanFolder = inherited.get(PREVIOUS + SCAN);
            if (scanFolder == null) {
                throw new IllegalStateException("Document sub-module is missing its upstream scan input");
            }
            SequencedProperties scan = SequencedProperties.ofFiles(scanFolder.resolve(SCAN_FILE));
            boolean hasJava = Boolean.parseBoolean(scan.getProperty(JAVADOC));
            boolean hasGroovy = Boolean.parseBoolean(scan.getProperty(GROOVYDOC));
            boolean hasScala = Boolean.parseBoolean(scan.getProperty(SCALADOC));
            boolean hasKotlin = Boolean.parseBoolean(scan.getProperty(DOKKA));

            SequencedSet<String> sourceInputs = new LinkedHashSet<>(inherited.sequencedKeySet());
            sourceInputs.remove(PREVIOUS + SCAN);

            int nonJava = (hasGroovy ? 1 : 0) + (hasScala ? 1 : 0) + (hasKotlin ? 1 : 0);
            if (nonJava == 1 && hasGroovy) {
                buildExecutor.addModule(GROOVYDOC,
                        new GroovyDocumentationModule(repositories, resolvers).pinning(pinning).includeJava(hasJava),
                        sourceInputs);
                return;
            }
            if (hasJava) {
                buildExecutor.addStep(JAVADOC,
                        process ? Javadoc.process() : Javadoc.tool(),
                        sourceInputs);
            }
            if (hasGroovy) {
                buildExecutor.addModule(GROOVYDOC,
                        new GroovyDocumentationModule(repositories, resolvers).pinning(pinning).within(GROOVYDOC),
                        sourceInputs);
            }
        }
    }
}
