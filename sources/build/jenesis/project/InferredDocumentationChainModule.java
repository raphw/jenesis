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
import build.jenesis.step.ProcessHandler;

public class InferredDocumentationChainModule implements BuildExecutorModule {

    public static final String JAVADOC = "javadoc", GROOVYDOC = "groovydoc", SCALADOC = "scaladoc", DOKKA = "dokka";
    public static final String DOCUMENT = "document", AGGREGATE = "aggregate";
    private static final String SCAN = "scan";
    private static final String SCAN_FILE = "scan.properties";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;

    public InferredDocumentationChainModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null);
    }

    private InferredDocumentationChainModule(Map<String, Repository> repositories,
                                             Map<String, Resolver> resolvers,
                                             Pinning pinning) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
    }

    public InferredDocumentationChainModule pinning(Pinning pinning) {
        return new InferredDocumentationChainModule(repositories, resolvers, pinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(SCAN, new Scan(), inherited.sequencedKeySet());
        SequencedSet<String> documentInputs = new LinkedHashSet<>(inherited.sequencedKeySet());
        documentInputs.add(SCAN);
        buildExecutor.addModule(DOCUMENT,
                new Document(repositories, resolvers, pinning),
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

            SequencedSet<String> outputs = new LinkedHashSet<>();
            if (hasKotlin && !hasScala && !hasGroovy) {
                buildExecutor.addModule(DOKKA,
                        new DokkaDocumentationModule(repositories, resolvers).pinning(pinning),
                        sourceInputs);
                outputs.add(DOKKA);
            } else if (hasGroovy && !hasScala && !hasKotlin) {
                buildExecutor.addModule(GROOVYDOC,
                        new GroovyDocumentationModule(repositories, resolvers).pinning(pinning).includeJava(hasJava),
                        sourceInputs);
                outputs.add(GROOVYDOC);
            } else if (hasScala && !hasJava && !hasKotlin && !hasGroovy) {
                buildExecutor.addModule(SCALADOC,
                        new ScalaDocumentationModule(repositories, resolvers).pinning(pinning),
                        sourceInputs);
                outputs.add(SCALADOC);
            } else if (hasJava && !hasKotlin && !hasScala && !hasGroovy) {
                buildExecutor.addStep(JAVADOC,
                        new Javadoc(ProcessHandler.Factory.of()).classpath(),
                        sourceInputs);
                outputs.add(JAVADOC);
            } else {
                if (hasJava) {
                    buildExecutor.addStep(JAVADOC,
                            new Javadoc(ProcessHandler.Factory.of()).classpath(),
                            sourceInputs);
                    outputs.add(JAVADOC);
                }
                if (hasKotlin) {
                    buildExecutor.addModule(DOKKA,
                            new DokkaDocumentationModule(repositories, resolvers).pinning(pinning).within(DOKKA),
                            sourceInputs);
                    outputs.add(DOKKA);
                }
                if (hasScala) {
                    buildExecutor.addModule(SCALADOC,
                            new ScalaDocumentationModule(repositories, resolvers).pinning(pinning).within(SCALADOC),
                            sourceInputs);
                    outputs.add(SCALADOC);
                }
                if (hasGroovy) {
                    buildExecutor.addModule(GROOVYDOC,
                            new GroovyDocumentationModule(repositories, resolvers).pinning(pinning).within(GROOVYDOC),
                            sourceInputs);
                    outputs.add(GROOVYDOC);
                }
            }
            buildExecutor.addStep(AGGREGATE, new Aggregate(), outputs);
        }

        @Override
        public Optional<String> resolve(String path) {
            return path.equals(AGGREGATE) ? Optional.of(AGGREGATE) : Optional.empty();
        }
    }

    private static class Aggregate implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path combined = Files.createDirectories(context.next().resolve(Javadoc.JAVADOC));
            for (BuildStepArgument argument : arguments.values()) {
                Path source = argument.folder().resolve(Javadoc.JAVADOC);
                if (!Files.exists(source)) {
                    continue;
                }
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(combined.resolve(source.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        BuildStep.linkOrCopy(combined.resolve(source.relativize(file)), file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            if (!Files.exists(combined.resolve("index.html"))) {
                SequencedSet<String> rendered = new TreeSet<>();
                try (Stream<Path> entries = Files.list(combined)) {
                    for (Path entry : entries.toList()) {
                        if (Files.isDirectory(entry) && Files.exists(entry.resolve("index.html"))) {
                            rendered.add(entry.getFileName().toString());
                        }
                    }
                }
                StringBuilder body = new StringBuilder();
                body.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                        .append("<title>API documentation</title></head><body>\n")
                        .append("<h1>API documentation</h1>\n");
                if (rendered.isEmpty()) {
                    body.append("<p>No rendered API documentation is available for this module.</p>\n");
                } else {
                    body.append("<p>API documentation is available per language:</p>\n<ul>\n");
                    for (String name : rendered) {
                        body.append("<li><a href=\"").append(name).append("/index.html\">")
                                .append(name).append("</a></li>\n");
                    }
                    body.append("</ul>\n");
                }
                body.append("</body></html>\n");
                Files.writeString(combined.resolve("index.html"), body.toString());
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
