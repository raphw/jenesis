package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.PathPlacement;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Bundle;
import build.jenesis.step.Inventory;
import build.jenesis.step.JLink;
import build.jenesis.step.JMod;
import build.jenesis.step.JPackage;
import build.jenesis.step.Jar;
import build.jenesis.step.NativeImage;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Sbom;

public record InferredMultiProjectAssembler(String packaging,
                                            boolean jmod,
                                            boolean jlink,
                                            boolean bundle,
                                            boolean launcher,
                                            boolean nativeImage,
                                            UnaryOperator<InferredSourceCodeQualityModule> check,
                                            UnaryOperator<InferredSourceFormattingModule> format,
                                            UnaryOperator<InferredByteCodeQualityModule> validate,
                                            UnaryOperator<InferredTestObservationModule> observe,
                                            UnaryOperator<TestModule> test,
                                            UnaryOperator<InferredComplianceModule> compliance) implements MultiProjectAssembler<ProjectModuleDescriptor> {

    public InferredMultiProjectAssembler() {
        String packagingOverride = System.getProperty("jenesis.java.jpackage");
        this(packagingOverride == null ? null : (packagingOverride.isEmpty() ? "app-image" : packagingOverride),
                Boolean.getBoolean("jenesis.java.jmod"),
                Boolean.getBoolean("jenesis.java.jlink"),
                Boolean.getBoolean("jenesis.java.bundle"),
                Boolean.getBoolean("jenesis.java.launcher"),
                Boolean.getBoolean("jenesis.java.native"),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity());
    }

    public InferredMultiProjectAssembler packaging(String packaging) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler jmod(boolean jmod) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler jlink(boolean jlink) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler bundle(boolean bundle) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler launcher(boolean launcher) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler nativeImage(boolean nativeImage) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler check(UnaryOperator<InferredSourceCodeQualityModule> check) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler format(UnaryOperator<InferredSourceFormattingModule> format) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler validate(UnaryOperator<InferredByteCodeQualityModule> validate) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler observe(UnaryOperator<InferredTestObservationModule> observe) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler test(UnaryOperator<TestModule> test) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler compliance(UnaryOperator<InferredComplianceModule> compliance) {
        return new InferredMultiProjectAssembler(packaging, jmod, jlink, bundle, launcher, nativeImage, check, format, validate, observe, test, compliance);
    }

    @Override
    public AssemblyDescriptor apply(ProjectModuleDescriptor descriptor,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        ProcessHandler.Factory factory = ProcessHandler.Factory.of();
        AssemblyDescriptor assembly = new AssemblyDescriptor((sub, outerInherited) -> {
            sub.addStep("prepare",
                    new Prepare(descriptor.pathPlacement()),
                    outerInherited.sequencedKeySet().stream());
            sub.addModule("check",
                    check.apply(new InferredSourceCodeQualityModule(descriptor.configuration(), repositories, resolvers)
                            .pinning(descriptor.pinning())),
                    descriptor.sources());
            sub.addModule("format",
                    format.apply(new InferredSourceFormattingModule(descriptor.configuration(), repositories, resolvers)
                            .pinning(descriptor.pinning())),
                    descriptor.sources());
            Sbom sbom = Boolean.parseBoolean(System.getProperty("jenesis.sbom.cyclonedx", "true"))
                    ? Sbom.configured(BuildStep.locate(descriptor.configuration(), "sbom.properties"))
                    : null;
            if (sbom != null) {
                sub.addStep("sbom", sbom,
                        Stream.concat(descriptor.manifests().stream(), descriptor.artifacts().stream()));
            }
            sub.addModule("compliance", compliance.apply(new InferredComplianceModule(descriptor.configuration())),
                    Stream.concat(descriptor.manifests().stream(), descriptor.artifacts().stream()));
            sub.addModule("binary", new JavaToolchainModule()
                            .compiler(new InferredCompilerChainModule(repositories, resolvers)
                                    .pinning(descriptor.pinning())
                                    .pathPlacement(descriptor.pathPlacement()))
                            .validator(validate.apply(new InferredByteCodeQualityModule(descriptor.configuration(), repositories, resolvers)
                                    .pinning(descriptor.pinning())))
                            .archiver(new Jar(factory, Jar.Sort.CLASSES).asModule("jar")),
                    Stream.of(
                            Stream.of("prepare"),
                            inputs(descriptor),
                            descriptor.resources().stream(),
                            sbom == null ? Stream.<String>empty() : Stream.of("sbom"))
                            .flatMap(Function.identity()));
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
                        sub.addModule("observed", observe.apply(new InferredTestObservationModule(
                                descriptor.configuration(),
                                repositories,
                                resolvers,
                                descriptor.pinning(),
                                engines -> test.apply(new TestModule(repositories, resolvers)
                                        .observe(engines)
                                        .pinning(descriptor.pinning())
                                        .pathPlacement(descriptor.pathPlacement())
                                        .moduleName(properties.getProperty("module")))
                                )), Stream.concat(Stream.of("prepare", "binary"), inputs(descriptor)));
                    }
                }
            }
            if (descriptor.source()) {
                sub.addModule("sources", (module, inherited) ->
                        module.addStep("archive",
                                new Jar(factory, Jar.Sort.SOURCES),
                                inherited.sequencedKeySet()), descriptor.sources());
            }
            if (descriptor.documentation()) {
                sub.addModule("documentation", (module, inherited) -> {
                    module.addModule("generate",
                            new InferredDocumentationChainModule(repositories, resolvers)
                                    .pinning(descriptor.pinning()),
                            inherited.sequencedKeySet());
                    module.addStep("archive",
                            new Jar(factory, Jar.Sort.JAVADOC),
                            "generate");
                }, Stream.concat(Stream.of("binary"), inputs(descriptor)));
            }
            if (jmod) {
                sub.addStep("jmod",
                        new JMod(factory),
                        Stream.concat(Stream.of("binary"), descriptor.content().stream()));
            }
        });
        if (jlink || packaging != null || bundle || launcher || nativeImage) {
            assembly = assembly.then("package", (sub, inherited) -> {
                SequencedSet<String> images = new LinkedHashSet<>();
                if (jlink) {
                    sub.addStep("jlink", new JLink(factory), inherited.sequencedKeySet().stream());
                    images.add("jlink");
                }
                if (packaging != null) {
                    sub.addStep("jpackage", new JPackage(factory, packaging), jlink
                            ? Stream.concat(Stream.of("jlink"), inherited.sequencedKeySet().stream())
                            : inherited.sequencedKeySet().stream());
                    images.add("jpackage");
                }
                if (bundle) {
                    sub.addStep("bundle", new Bundle(), inherited.sequencedKeySet().stream());
                }
                if (launcher) {
                    sub.addModule("launcher",
                            new LauncherModule(repositories, resolvers)
                                    .pinning(descriptor.pinning())
                                    .pathPlacement(descriptor.pathPlacement()),
                            inherited.sequencedKeySet().stream());
                }
                if (nativeImage) {
                    sub.addStep("reachability", new NativeImageMetadata(), inherited.sequencedKeySet().stream());
                    sub.addStep("native-image", new NativeImage(descriptor.pathPlacement()),
                            Stream.concat(inherited.sequencedKeySet().stream(), Stream.of("reachability")));
                    images.add("native-image");
                }
                if (!images.isEmpty()) {
                    sub.addStep("inventory", new Inventory(), images.stream());
                }
            });
        }
        return assembly;
    }

    private static Stream<String> inputs(ProjectModuleDescriptor descriptor) {
        return Stream.of(descriptor.sources(),
                descriptor.manifests(),
                descriptor.artifacts()).flatMap(SequencedSet::stream);
    }

    private record Prepare(PathPlacement pathPlacement) implements BuildStep {

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
                if (pathPlacement.modular() && moduleName != null) {
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
                SequencedProperties launcher = new SequencedProperties();
                launcher.setProperty("mainClass", main);
                if (pathPlacement.modular() && moduleName != null) {
                    launcher.setProperty("mainModule", moduleName);
                }
                if (artifact != null) {
                    launcher.setProperty("name", artifact);
                }
                launcher.store(context.next().resolve("launcher.properties"));
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
