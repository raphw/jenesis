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

public record InferredMultiProjectAssembler(Function<InferredSourceCodeQualityModule, BuildExecutorModule> check,
                                            Function<InferredSourceFormattingModule, BuildExecutorModule> format,
                                            Function<InferredByteCodeQualityModule, BuildExecutorModule> validate,
                                            Function<InferredTestObservationModule, BuildExecutorModule> observe,
                                            Function<TestModule, BuildExecutorModule> test,
                                            Function<InferredComplianceModule, BuildExecutorModule> compliance) implements MultiProjectAssembler<ProjectModuleDescriptor> {

    public InferredMultiProjectAssembler() {
        this(module -> module,
                module -> module,
                module -> module,
                module -> module,
                module -> module,
                module -> module);
    }

    public InferredMultiProjectAssembler check(Function<InferredSourceCodeQualityModule, BuildExecutorModule> check) {
        return new InferredMultiProjectAssembler(check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler format(Function<InferredSourceFormattingModule, BuildExecutorModule> format) {
        return new InferredMultiProjectAssembler(check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler validate(Function<InferredByteCodeQualityModule, BuildExecutorModule> validate) {
        return new InferredMultiProjectAssembler(check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler observe(Function<InferredTestObservationModule, BuildExecutorModule> observe) {
        return new InferredMultiProjectAssembler(check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler test(Function<TestModule, BuildExecutorModule> test) {
        return new InferredMultiProjectAssembler(check, format, validate, observe, test, compliance);
    }

    public InferredMultiProjectAssembler compliance(Function<InferredComplianceModule, BuildExecutorModule> compliance) {
        return new InferredMultiProjectAssembler(check, format, validate, observe, test, compliance);
    }

    @Override
    public AssemblyDescriptor apply(ProjectModuleDescriptor descriptor,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        Packaging packaging;
        try {
            packaging = Packaging.configured(BuildStep.locate(descriptor.configuration(), "packaging.properties"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ProcessHandler.Factory factory = ProcessHandler.Factory.of();
        AssemblyDescriptor assembly = new AssemblyDescriptor((sub, outerInherited) -> {
            sub.addStep("prepare",
                    new Prepare(descriptor.pathPlacement(), descriptor.configuration()),
                    outerInherited.sequencedKeySet().stream());
            sub.addModule("check",
                    check.apply(new InferredSourceCodeQualityModule(descriptor.configuration(), repositories, resolvers)
                            .pinning(descriptor.pinning())),
                    Stream.concat(descriptor.sources().stream(), descriptor.spdx().stream()));
            sub.addModule("format",
                    format.apply(new InferredSourceFormattingModule(descriptor.configuration(), repositories, resolvers)
                            .pinning(descriptor.pinning())),
                    Stream.concat(descriptor.sources().stream(), descriptor.spdx().stream()));
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
            if (packaging.jmod()) {
                sub.addStep("jmod",
                        new JMod(factory),
                        Stream.concat(Stream.of("binary"), descriptor.content().stream()));
            }
        });
        if (packaging.jlink() || packaging.jpackage() != null || packaging.bundle() || packaging.launcher() || packaging.nativeImage()) {
            assembly = assembly.then("package", (sub, inherited) -> {
                SequencedSet<String> images = new LinkedHashSet<>();
                if (packaging.jlink()) {
                    sub.addStep("jlink", new JLink(factory), inherited.sequencedKeySet().stream());
                    images.add("jlink");
                }
                if (packaging.jpackage() != null) {
                    sub.addStep("jpackage", new JPackage(factory, packaging.jpackage()), packaging.jlink()
                            ? Stream.concat(Stream.of("jlink"), inherited.sequencedKeySet().stream())
                            : inherited.sequencedKeySet().stream());
                    images.add("jpackage");
                }
                if (packaging.bundle()) {
                    sub.addStep("bundle", new Bundle(), inherited.sequencedKeySet().stream());
                }
                if (packaging.launcher()) {
                    sub.addModule("launcher",
                            new LauncherModule(repositories, resolvers)
                                    .pinning(descriptor.pinning())
                                    .pathPlacement(descriptor.pathPlacement()),
                            inherited.sequencedKeySet().stream());
                }
                if (packaging.nativeImage()) {
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

    private record Packaging(boolean jmod,
                            boolean jlink,
                            boolean bundle,
                            boolean launcher,
                            boolean nativeImage,
                            String jpackage) {

        private static Packaging configured(Path properties) throws IOException {
            if (properties == null) {
                return new Packaging(false, false, false, false, false, null);
            }
            SequencedProperties configuration = SequencedProperties.ofFiles(properties);
            String jpackage = configuration.getProperty("jpackage");
            if (jpackage != null) {
                jpackage = jpackage.trim();
                if (jpackage.isEmpty()) {
                    jpackage = null;
                }
            }
            return new Packaging(flag(configuration, "jmod"),
                    flag(configuration, "jlink"),
                    flag(configuration, "bundle"),
                    flag(configuration, "launcher"),
                    flag(configuration, "native"),
                    jpackage);
        }

        private static boolean flag(SequencedProperties configuration, String key) {
            String value = configuration.getProperty(key);
            return value != null && Boolean.parseBoolean(value.trim());
        }
    }

    private static Stream<String> inputs(ProjectModuleDescriptor descriptor) {
        return Stream.of(descriptor.sources(),
                descriptor.manifests(),
                descriptor.artifacts(),
                descriptor.spdx()).flatMap(SequencedSet::stream);
    }

    private record Prepare(PathPlacement pathPlacement, SequencedSet<Path> configuration) implements BuildStep {

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
            SequencedMap<String, SequencedProperties> overrides = new LinkedHashMap<>();
            for (Path folder : configuration) {
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "process-*.properties")) {
                    for (Path file : files) {
                        String fileName = file.getFileName().toString();
                        String command = fileName.substring("process-".length(), fileName.length() - ".properties".length());
                        if (!overrides.containsKey(command)) {
                            overrides.put(command, SequencedProperties.ofFiles(file));
                        }
                    }
                }
            }
            if (!overrides.isEmpty() && processFolder == null) {
                processFolder = Files.createDirectories(context.next().resolve(ProcessBuildStep.PROCESS));
            }
            for (Map.Entry<String, SequencedProperties> override : overrides.entrySet()) {
                Path target = processFolder.resolve(override.getKey() + ".properties");
                SequencedProperties merged = Files.isRegularFile(target)
                        ? SequencedProperties.ofFiles(target)
                        : new SequencedProperties();
                for (String key : override.getValue().stringPropertyNames()) {
                    merged.setProperty(key, override.getValue().getProperty(key));
                }
                merged.store(target);
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
