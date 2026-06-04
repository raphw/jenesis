package build.jenesis.project;

import module java.base;
import build.jenesis.DependencyScope;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.PathPlacement;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.JLink;
import build.jenesis.step.JMod;
import build.jenesis.step.JPackage;
import build.jenesis.step.Jar;
import build.jenesis.step.Javadoc;
import build.jenesis.step.ProcessBuildStep;

public record JavaMultiProjectAssembler(boolean process,
                                        String filter,
                                        String packaging,
                                        boolean jmod,
                                        boolean jlink,
                                        TestEngine testEngine,
                                        String group,
                                        boolean parallel,
                                        boolean reporting) implements MultiProjectAssembler<ProjectModuleDescriptor> {

    public JavaMultiProjectAssembler() {
        this(false, null, null, false, false, null, null, false, false);
    }

    public JavaMultiProjectAssembler process(boolean process) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler filter(String filter) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler packaging(String packaging) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler jmod(boolean jmod) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler jlink(boolean jlink) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler testEngine(TestEngine testEngine) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler group(String group) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler parallel(boolean parallel) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    public JavaMultiProjectAssembler reporting(boolean reporting) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink, testEngine, group, parallel, reporting);
    }

    @Override
    public JavaMultiProjectAssembler resolveProperties() {
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        String filterOverride = System.getProperty("jenesis.java.test.filter");
        String packagingOverride = System.getProperty("jenesis.java.package");
        String groupOverride = System.getProperty("jenesis.java.test.group");
        return new JavaMultiProjectAssembler(
                process || nativeImage || Boolean.getBoolean("jenesis.java.process"),
                filterOverride != null ? filterOverride : filter,
                packagingOverride == null ? packaging : (packagingOverride.isEmpty() ? "app-image" : packagingOverride),
                jmod || Boolean.getBoolean("jenesis.java.jmod"),
                jlink || Boolean.getBoolean("jenesis.java.jlink"),
                testEngine,
                groupOverride != null ? groupOverride : group,
                parallel || Boolean.getBoolean("jenesis.java.test.parallel"),
                reporting || Boolean.getBoolean("jenesis.project.tests.reporting"));
    }

    @Override
    public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                     Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers) {
        return (sub, outerInherited) -> {
            sub.addStep("prepare",
                    new Prepare(descriptor.modulePath()),
                    outerInherited.sequencedKeySet().stream());
            sub.addModule("binary", new JavaToolchainModule()
                    .compiler(new InferredCompilerChainModule(repositories, resolvers)
                            .process(process)
                            .pinning(descriptor.pinning())
                            .modulePath(descriptor.modulePath()))
                    .archiver((process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES)).asModule("jar")),
                    Stream.concat(
                            Stream.of("prepare"),
                            Stream.concat(inputs(descriptor), descriptor.resources().stream())));
            if (descriptor.test()) {
                Path module = null;
                for (String manifest : descriptor.manifests()) {
                    Path candidate = outerInherited.get(manifest);
                    if (candidate != null && Files.isRegularFile(candidate.resolve(BuildStep.MODULE))) {
                        module = candidate.resolve(BuildStep.MODULE);
                        break;
                    }
                }
                if (module != null) {
                    SequencedProperties properties = SequencedProperties.ofFiles(module);
                    if (properties.getProperty("test") != null) {
                        sub.addModule("test",
                                new TestModule(repositories, resolvers)
                                        .engine(testEngine)
                                        .filter(filter)
                                        .group(group)
                                        .parallel(parallel)
                                        .reporting(reporting)
                                        .pinning(descriptor.pinning())
                                        .modulePath(descriptor.modulePath())
                                        .moduleName(properties.getProperty("module")),
                                Stream.concat(Stream.of("prepare", "binary"), inputs(descriptor)));
                    }
                }
            }
            if (descriptor.source()) {
                sub.addModule("sources", (module, inherited) ->
                        module.addStep("archive",
                            process ? Jar.process(Jar.Sort.SOURCES) : Jar.tool(Jar.Sort.SOURCES),
                            inherited.sequencedKeySet()), descriptor.sources());
            }
            if (descriptor.documentation()) {
                sub.addModule("documentation", (module, inherited) -> {
                    module.addStep("javadoc",
                            process ? Javadoc.process() : Javadoc.tool(),
                            inherited.sequencedKeySet());
                    module.addStep("archive",
                            process ? Jar.process(Jar.Sort.JAVADOC) : Jar.tool(Jar.Sort.JAVADOC),
                            "javadoc");
                }, inputs(descriptor));
            }
            if (jmod) {
                sub.addStep("jmod",
                        process ? JMod.process() : JMod.tool(),
                        Stream.concat(Stream.of("binary"), descriptor.content().stream()));
            }
            if (jlink) {
                sub.addStep("jlink",
                        process ? JLink.process() : JLink.tool(),
                        Stream.concat(
                                Stream.of("prepare", jmod ? "jmod" : "binary"),
                                descriptor.artifacts(DependencyScope.RUNTIME).stream()));
            }
            if (packaging != null) {
                Stream<String> inputs = Stream.concat(
                        Stream.of("prepare", "binary"),
                        descriptor.artifacts(DependencyScope.RUNTIME).stream());
                sub.addStep("jpackage",
                        process ? JPackage.process(packaging) : JPackage.tool(packaging),
                        jlink ? Stream.concat(Stream.of("jlink"), inputs) : inputs);
            }
        };
    }

    private static Stream<String> inputs(ProjectModuleDescriptor descriptor) {
        return Stream.of(
                        descriptor.sources(),
                        descriptor.manifests(),
                        descriptor.resolved(DependencyScope.COMPILE),
                        descriptor.resolved(DependencyScope.RUNTIME),
                        descriptor.artifacts(DependencyScope.COMPILE),
                        descriptor.artifacts(DependencyScope.RUNTIME))
                .flatMap(SequencedSet::stream);
    }

    private record Prepare(PathPlacement modulePath) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            String main = null;
            String version = null;
            String artifact = null;
            String moduleName = null;
            for (BuildStepArgument argument : arguments.values()) {
                Path moduleFile = argument.folder().resolve(BuildStep.MODULE);
                if (Files.isRegularFile(moduleFile)) {
                    SequencedProperties module = SequencedProperties.ofFiles(moduleFile);
                    if (main == null) {
                        String value = module.getProperty("main");
                        if (value != null && !value.isEmpty()) {
                            main = value;
                        }
                    }
                    if (moduleName == null) {
                        String value = module.getProperty("module");
                        if (value != null && !value.isEmpty()) {
                            moduleName = value;
                        }
                    }
                }
                Path metadataFile = argument.folder().resolve(BuildStep.METADATA);
                if (Files.isRegularFile(metadataFile)) {
                    SequencedProperties metadata = SequencedProperties.ofFiles(metadataFile);
                    if (version == null) {
                        String value = metadata.getProperty("version");
                        if (value != null && !value.isEmpty()) {
                            version = value;
                        }
                    }
                    if (artifact == null) {
                        String value = metadata.getProperty("artifact");
                        if (value != null && !value.isEmpty()) {
                            artifact = value;
                        }
                    }
                }
            }
            Path processFolder = null;
            if (main != null) {
                processFolder = Files.createDirectories(context.next().resolve(ProcessBuildStep.PROCESS));
                SequencedProperties jar = new SequencedProperties();
                jar.setProperty("--main-class", main);
                jar.store(processFolder.resolve("jar.properties"));
                SequencedProperties jpackage = new SequencedProperties();
                if (artifact != null) {
                    jpackage.setProperty("--name", artifact);
                }
                if (modulePath.modular() && moduleName != null) {
                    jpackage.setProperty("--module", moduleName + "/" + main);
                } else {
                    jpackage.setProperty("--main-jar", Jar.Sort.CLASSES.getFile());
                    jpackage.setProperty("--main-class", main);
                }
                if (version != null) {
                    String appVersion = appVersion(version);
                    if (appVersion != null) {
                        jpackage.setProperty("--app-version", appVersion);
                    }
                }
                jpackage.store(processFolder.resolve("jpackage.properties"));
            }
            if (moduleName != null) {
                if (processFolder == null) {
                    processFolder = Files.createDirectories(context.next().resolve(ProcessBuildStep.PROCESS));
                }
                SequencedProperties jlink = new SequencedProperties();
                jlink.setProperty("--add-modules", moduleName);
                jlink.store(processFolder.resolve("jlink.properties"));
            }
            if (version != null) {
                if (processFolder == null) {
                    processFolder = Files.createDirectories(context.next().resolve(ProcessBuildStep.PROCESS));
                }
                SequencedProperties javac = new SequencedProperties();
                javac.setProperty("--module-version", version);
                javac.store(processFolder.resolve("javac.properties"));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        private static String appVersion(String version) {
            int end = 0;
            while (end < version.length()
                    && (Character.isDigit(version.charAt(end)) || version.charAt(end) == '.')) {
                end++;
            }
            String prefix = version.substring(0, end);
            while (prefix.startsWith(".")) {
                prefix = prefix.substring(1);
            }
            while (prefix.endsWith(".")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            return prefix.isEmpty() ? null : prefix;
        }
    }
}
