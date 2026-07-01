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
import build.jenesis.step.Bind;
import build.jenesis.step.Dependencies;
import build.jenesis.step.Javac;
import build.jenesis.step.Javadoc;
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;

public class GroovyDocumentationModule implements BuildExecutorModule {

    public static final String DOCUMENTED = "documented";
    private static final String REQUIRED = "required", ARTIFACTS = "artifacts",
            DEPENDENCIES = "dependencies";

    private static final List<String> PREFERRED_PREFIXES = List.of("maven", "module");
    private static final String MODULE_NAME = "org.apache.groovy.groovydoc";
    private static final String MAVEN_GROUP = "org.apache.groovy";
    private static final String MAVEN_ARTIFACT = "groovy-groovydoc";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String tool;
    private final String group;
    private final String within;
    private final boolean includeJava;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;
    private final Boolean printing;

    public GroovyDocumentationModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "groovydoc", "main", null, false, null, null);
    }

    private GroovyDocumentationModule(Map<String, Repository> repositories,
                                      Map<String, Resolver> resolvers,
                                      Pinning pinning,
                                      String tool,
                                      String group,
                                      String within,
                                      boolean includeJava,
                                      Function<List<String>, ? extends ProcessHandler> factory,
                                      Boolean printing) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.tool = tool;
        this.group = group;
        this.within = within;
        this.includeJava = includeJava;
        this.factory = factory;
        this.printing = printing;
    }

    public GroovyDocumentationModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, tool, group, within, includeJava, factory, printing);
    }

    public GroovyDocumentationModule pinning(Pinning pinning) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, tool, group, within, includeJava, factory, printing);
    }

    public GroovyDocumentationModule tool(String tool) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, tool, group, within, includeJava, factory, printing);
    }

    public GroovyDocumentationModule group(String group) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, tool, group, within, includeJava, factory, printing);
    }

    public GroovyDocumentationModule within(String within) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, tool, group, within, includeJava, factory, printing);
    }

    public GroovyDocumentationModule includeJava(boolean includeJava) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, tool, group, within, includeJava, factory, printing);
    }

    public GroovyDocumentationModule printing(boolean printing) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, tool, group, within, includeJava, factory, printing);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(Set.copyOf(resolvers.keySet()), tool), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> documentInputs = new LinkedHashSet<>();
        documentInputs.add(DEPENDENCIES);
        documentInputs.addAll(upstream);
        buildExecutor.addStep(DOCUMENTED,
                factory == null ? new Document(within, includeJava, tool, group, printing) : new Document(within, includeJava, tool, group, factory, printing),
                documentInputs);
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(DOCUMENTED)) {
            return Optional.of(DOCUMENTED);
        }
        if (path.equals(DEPENDENCIES)) {
            return Optional.of(ARTIFACTS);
        }
        return Optional.empty();
    }

    private record Requires(Set<String> prefixes, String tool) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            String selectedPrefix = null;
            for (String prefix : PREFERRED_PREFIXES) {
                if (prefixes.contains(prefix)) {
                    selectedPrefix = prefix;
                    break;
                }
            }
            if (selectedPrefix == null) {
                throw new IllegalStateException(
                        "No suitable resolver for Groovy documentation. Available prefixes: " + prefixes
                                + ". Expected one of: " + PREFERRED_PREFIXES);
            }
            String coordinate = switch (selectedPrefix) {
                case "module" -> selectedPrefix + "/" + MODULE_NAME;
                case "maven" -> selectedPrefix + "/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/RELEASE";
                default -> throw new IllegalStateException("Unreachable");
            };
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(tool + "/runtime/" + coordinate, "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Document extends JdkProcessBuildStep {

        private final String within;
        private final boolean includeJava;
        private final String tool;
        private final String group;

        private Document(String within, boolean includeJava, String tool, String group, Boolean printing) {
            this(within, includeJava, tool, group, ProcessHandler.OfProcess.ofJavaHome("bin/java"), printing);
        }

        private Document(String within, boolean includeJava, String tool, String group, Function<List<String>, ? extends ProcessHandler> factory, Boolean printing) {
            super("groovydoc", factory, printing == null ? ProcessBuildStep.printing("groovydoc") : printing);
            this.within = within;
            this.includeJava = includeJava;
            this.tool = tool;
            this.group = group;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return Javac.hasRelevantChange(arguments,
                    Set.of(".groovy", ".java"),
                    Set.of("groovydoc.properties"));
        }

        @Override
        public boolean acceptableExitCode(int code,
                                          Executor executor,
                                          BuildStepContext context,
                                          SequencedMap<String, BuildStepArgument> arguments) {
            return true;
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            Path documentation = context.next().resolve(Javadoc.JAVADOC);
            Path output = Files.createDirectories(within == null ? documentation : documentation.resolve(within));
            List<String> roots = new ArrayList<>(), rootFiles = new ArrayList<>(), jars = new ArrayList<>(), classpath = new ArrayList<>();
            SequencedSet<String> packages = new TreeSet<>();
            boolean[] anyGroovy = new boolean[1];
            String release = null;
            for (BuildStepArgument argument : arguments.values()) {
                Path classes = argument.folder().resolve(BuildStep.CLASSES);
                if (Files.exists(classes)) {
                    classpath.add(classes.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), tool, "runtime")) {
                    jars.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), group, "compile")) {
                    jars.add(jar.toString());
                }
                Path javacProperties = argument.folder().resolve(ProcessBuildStep.PROCESS + "javac.properties");
                if (Files.exists(javacProperties)) {
                    SequencedProperties loaded = SequencedProperties.ofFiles(javacProperties);
                    String value = loaded.getProperty("--release");
                    if (value != null && !value.isEmpty()) {
                        release = value;
                    }
                }
                Path sources = argument.folder().resolve(Bind.SOURCES);
                if (Files.exists(sources)) {
                    roots.add(sources.toString());
                    Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString();
                            boolean groovy = name.endsWith(".groovy");
                            boolean java = includeJava && name.endsWith(".java") && !name.equals("module-info.java");
                            if (groovy || java) {
                                anyGroovy[0] |= groovy;
                                Path parent = sources.relativize(file).getParent();
                                if (parent == null) {
                                    rootFiles.add(file.toString());
                                } else {
                                    packages.add(parent.toString().replace(File.separatorChar, '.'));
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            if (!anyGroovy[0]) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException(
                        "No groovydoc jars resolved upstream of the Groovy documentation step");
            }
            List<String> launch = new ArrayList<>(jars);
            launch.addAll(classpath);
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, launch),
                    "org.codehaus.groovy.tools.groovydoc.Main",
                    "-d", output.toString(),
                    "-notimestamp",
                    "-javaVersion", languageLevel(release),
                    "-sourcepath", String.join(File.pathSeparator, roots)));
            commands.addAll(packages);
            commands.addAll(rootFiles);
            return CompletableFuture.completedStage(commands);
        }

        private static String languageLevel(String release) {
            String feature = release == null ? "21" : release;
            if (feature.startsWith("1.")) {
                feature = feature.substring(2);
            }
            int level;
            try {
                level = Integer.parseInt(feature);
            } catch (NumberFormatException e) {
                level = 21;
            }
            if (level < 8) {
                level = 8;
            } else if (level > 25) {
                level = 25;
            }
            return "JAVA_" + level;
        }
    }
}
