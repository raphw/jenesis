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
import build.jenesis.step.Dependencies;
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;

public class PiTestModule implements BuildExecutorModule {

    public static final String MUTATE = "mutate";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String tool;
    private final String group;
    private final SequencedProperties config;
    private final Boolean printing;

    public PiTestModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "pitest", "main", new SequencedProperties(), null);
    }

    private PiTestModule(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning,
                         String tool,
                         String group,
                         SequencedProperties config,
                         Boolean printing) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.tool = tool;
        this.group = group;
        this.config = config;
        this.printing = printing;
    }

    public PiTestModule pinning(Pinning pinning) {
        return new PiTestModule(repositories, resolvers, pinning, tool, group, config, printing);
    }

    public PiTestModule tool(String tool) {
        return new PiTestModule(repositories, resolvers, pinning, tool, group, config, printing);
    }

    public PiTestModule group(String group) {
        return new PiTestModule(repositories, resolvers, pinning, tool, group, config, printing);
    }

    public PiTestModule config(SequencedProperties config) {
        return new PiTestModule(repositories, resolvers, pinning, tool, group, config, printing);
    }

    public PiTestModule printing(boolean printing) {
        return new PiTestModule(repositories, resolvers, pinning, tool, group, config, printing);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(REQUIRED, new Requires(tool), inherited.sequencedKeySet());
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> mutateInputs = new LinkedHashSet<>();
        mutateInputs.add(DEPENDENCIES);
        mutateInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(MUTATE, new Mutate(tool, group, config, printing), mutateInputs);
    }

    private record Requires(String tool) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return false;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            String launcher = junitPlatformVersion(arguments);
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(tool + "/runtime/maven/org.pitest/pitest-command-line/RELEASE", "");
            requires.setProperty(tool + "/runtime/maven/org.pitest/pitest-junit5-plugin/RELEASE", "");
            if (launcher != null) {
                requires.setProperty(tool + "/classpath/maven/org.junit.platform/junit-platform-launcher/" + launcher, "");
            }
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        private static String junitPlatformVersion(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
            for (BuildStepArgument argument : arguments.values()) {
                Path versions = argument.folder().resolve(BuildStep.VERSIONS);
                if (!Files.isRegularFile(versions)) {
                    continue;
                }
                SequencedProperties properties = SequencedProperties.ofFiles(versions);
                for (String key : properties.stringPropertyNames()) {
                    if (key.endsWith("/org.junit.platform/junit-platform-engine")
                            || key.endsWith("/org.junit.platform/junit-platform-commons")) {
                        String value = properties.getProperty(key);
                        int space = value.indexOf(' ');
                        return space < 0 ? value : value.substring(0, space);
                    }
                }
            }
            return null;
        }
    }

    private static class Mutate extends JdkProcessBuildStep {

        private final String tool;
        private final String group;
        private final SequencedProperties config;

        private Mutate(String tool, String group, SequencedProperties config, Boolean printing) {
            super("pitest", ProcessHandler.OfProcess.ofJavaHome("bin/java"), printing == null ? ProcessBuildStep.printing("pitest") : printing);
            this.tool = tool;
            this.group = group;
            this.config = config;
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            List<String> tools = new ArrayList<>(), external = new ArrayList<>(), sources = new ArrayList<>();
            SequencedSet<String> candidates = new LinkedHashSet<>();
            Set<String> expanded = new HashSet<>();
            Path code = Files.createDirectories(context.supplement().resolve("code")).toAbsolutePath();
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), tool, "runtime")) {
                    tools.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), tool, "classpath")) {
                    external.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), group, "runtime")) {
                    String fileName = jar.getFileName().toString();
                    if (fileName.contains("%2F")) {
                        external.add(jar.toString());
                    } else if (expanded.add(fileName)) {
                        extract(jar, code);
                    }
                }
                Path classes = argument.folder().resolve(BuildStep.CLASSES);
                if (Files.isDirectory(classes)) {
                    copy(classes, code);
                }
                Path source = argument.folder().resolve(BuildStep.SOURCES);
                if (Files.isDirectory(source)) {
                    sources.add(source.toString());
                }
            }
            if (tools.isEmpty()) {
                throw new IllegalStateException("No PIT jars resolved upstream of the PIT mutation step");
            }
            Files.walkFileTree(code, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = code.relativize(file).toString();
                    if (name.endsWith(".class") && !name.equals("module-info.class")) {
                        candidates.add(name.substring(0, name.length() - 6).replace(File.separatorChar, '.'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (candidates.isEmpty() || sources.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            if (!matches(candidates, config.getProperty("targetTests", "*"))) {
                return CompletableFuture.completedStage(null);
            }
            Path report = Files.createDirectories(context.next().resolve(BuildStep.REPORTS + "pitest"));
            List<String> classPath = new ArrayList<>();
            classPath.add(code.toString());
            classPath.addAll(new LinkedHashSet<>(external));
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", tools.stream().distinct().collect(Collectors.joining(File.pathSeparator)),
                    "org.pitest.mutationtest.commandline.MutationCoverageReport",
                    "--reportDir", report.toString(),
                    "--classPath", String.join(",", classPath),
                    "--sourceDirs", String.join(",", sources),
                    "--targetClasses", config.getProperty("targetClasses", "*"),
                    "--targetTests", config.getProperty("targetTests", "*"),
                    "--outputFormats", config.getProperty("outputFormats", "XML"),
                    "--mutableCodePaths", code.toString(),
                    "--timestampedReports", "false",
                    "--failWhenNoMutations", config.getProperty("failWhenNoMutations", "false")));
            String mutators = config.getProperty("mutators");
            if (mutators != null) {
                commands.add("--mutators");
                commands.add(mutators);
            }
            return CompletableFuture.completedStage(commands);
        }

        private static void extract(Path jar, Path code) throws IOException {
            try (JarFile archive = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = archive.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (!entryName.endsWith(".class") || entryName.equals("module-info.class") || entryName.startsWith("META-INF/")) {
                        continue;
                    }
                    Path file = code.resolve(entryName);
                    Files.createDirectories(file.getParent());
                    try (InputStream input = archive.getInputStream(entry)) {
                        Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        private static void copy(Path classes, Path code) throws IOException {
            Files.walkFileTree(classes, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = classes.relativize(file).toString();
                    if (!name.equals("module-info.class")) {
                        Path target = code.resolve(name);
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private static boolean matches(SequencedSet<String> names, String globs) {
            for (String glob : globs.split(",")) {
                String trimmed = glob.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Pattern pattern = Pattern.compile(trimmed.replace(".", "\\.").replace("*", ".*"));
                for (String name : names) {
                    if (pattern.matcher(name).matches()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
