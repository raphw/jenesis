package build.jenesis.project;

import module java.base;
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
                                        boolean jlink) implements MultiProjectAssembler<ProjectModuleDescriptor> {

    public JavaMultiProjectAssembler() {
        this(false, null, null, false, false);
    }

    public JavaMultiProjectAssembler process(boolean process) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink);
    }

    public JavaMultiProjectAssembler filter(String filter) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink);
    }

    public JavaMultiProjectAssembler packaging(String packaging) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink);
    }

    public JavaMultiProjectAssembler jmod(boolean jmod) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink);
    }

    public JavaMultiProjectAssembler jlink(boolean jlink) {
        return new JavaMultiProjectAssembler(process, filter, packaging, jmod, jlink);
    }

    @Override
    public JavaMultiProjectAssembler resolveProperties() {
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        String filterOverride = System.getProperty("jenesis.java.test");
        String packagingOverride = System.getProperty("jenesis.java.package");
        return new JavaMultiProjectAssembler(
                process || nativeImage || Boolean.getBoolean("jenesis.java.process"),
                filterOverride != null ? filterOverride : filter,
                packagingOverride == null ? packaging : (packagingOverride.isEmpty() ? "app-image" : packagingOverride),
                jmod || Boolean.getBoolean("jenesis.java.jmod"),
                jlink || Boolean.getBoolean("jenesis.java.jlink"));
    }

    @Override
    public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                     Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers) {
        return (sub, outerInherited) -> {
            sub.addStep("prepare",
                    new Prepare(descriptor.modulePath()),
                    outerInherited.sequencedKeySet().stream());
            sub.addModule("java", new JavaToolchainModule(
                    new InferredCompilerChainModule(repositories, resolvers)
                            .process(process)
                            .strictPinning(descriptor.strictPinning())
                            .modulePath(descriptor.modulePath()),
                    (process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES)).asModule("jar")),
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
                                        .filter(filter)
                                        .strictPinning(descriptor.strictPinning())
                                        .modulePath(descriptor.modulePath())
                                        .moduleName(properties.getProperty("module")),
                                Stream.concat(Stream.of("java", "prepare"), inputs(descriptor)));
                    }
                }
            }
            if (descriptor.source()) {
                sub.addStep("sources",
                        process ? Jar.process(Jar.Sort.SOURCES) : Jar.tool(Jar.Sort.SOURCES),
                        descriptor.sources());
            }
            if (descriptor.documentation()) {
                sub.addModule("javadoc", (module, inherited) -> {
                    module.addStep("classes",
                            process ? Javadoc.process() : Javadoc.tool(),
                            inherited.sequencedKeySet().stream());
                    module.addStep("artifacts",
                            process ? Jar.process(Jar.Sort.JAVADOC) : Jar.tool(Jar.Sort.JAVADOC),
                            "classes");
                }, inputs(descriptor));
            }
            if (jmod) {
                sub.addStep("jmod",
                        process ? JMod.process() : JMod.tool(),
                        Stream.concat(Stream.of("java"), descriptor.content().stream()));
            }
            if (jlink) {
                sub.addStep("jlink",
                        process ? JLink.process() : JLink.tool(),
                        Stream.concat(
                                Stream.of("prepare", jmod ? "jmod" : "java"),
                                descriptor.artifacts(DependencyScope.RUNTIME).stream()));
            }
            if (packaging != null) {
                Stream<String> inputs = Stream.concat(
                        Stream.of("prepare", "java"),
                        descriptor.artifacts(DependencyScope.RUNTIME).stream());
                if (jlink) {
                    inputs = Stream.concat(Stream.of("jlink"), inputs);
                }
                sub.addStep("jpackage",
                        process ? JPackage.process(packaging) : JPackage.tool(packaging),
                        inputs);
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
    }
}
