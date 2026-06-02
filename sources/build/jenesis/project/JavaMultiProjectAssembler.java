package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.JPackage;
import build.jenesis.step.Jar;
import build.jenesis.step.Javadoc;
import build.jenesis.step.ProcessBuildStep;

public record JavaMultiProjectAssembler(boolean process,
                                        String filter,
                                        String packaging) implements MultiProjectAssembler<ProjectModuleDescriptor> {

    public JavaMultiProjectAssembler() {
        boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        String packaging = System.getProperty("jenesis.java.package");
        this(isNativeImage || Boolean.getBoolean("jenesis.java.process"),
                System.getProperty("jenesis.java.test"),
                packaging != null && packaging.isEmpty() ? "app-image" : packaging);
    }

    @Override
    public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                     Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers) {
        return (sub, outerInherited) -> {
            sub.addStep("prepare", new Prepare(), outerInherited.sequencedKeySet().stream());
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
                        sub.addModule("test", new TestModule(repositories, resolvers)
                                        .filter(filter)
                                        .strictPinning(descriptor.strictPinning())
                                        .modulePath(descriptor.modulePath())
                                        .moduleName(properties.getProperty("module")),
                                Stream.concat(Stream.of("java", "prepare"), inputs(descriptor)));
                    }
                }
            }
            if (descriptor.source()) {
                sub.addStep("sources", process ? Jar.process(Jar.Sort.SOURCES) : Jar.tool(Jar.Sort.SOURCES), descriptor.sources());
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
            if (packaging != null) {
                sub.addStep("package",
                        process ? JPackage.process(packaging) : JPackage.tool(packaging),
                        Stream.concat(
                                Stream.of("prepare", "java"),
                                descriptor.artifacts(DependencyScope.RUNTIME).stream()));
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

    private static class Prepare implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            String main = null;
            String version = null;
            String artifact = null;
            for (BuildStepArgument argument : arguments.values()) {
                if (main == null) {
                    Path moduleFile = argument.folder().resolve(BuildStep.MODULE);
                    if (Files.isRegularFile(moduleFile)) {
                        SequencedProperties module = SequencedProperties.ofFiles(moduleFile);
                        String value = module.getProperty("main");
                        if (value != null && !value.isEmpty()) {
                            main = value;
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
                jpackage.setProperty("--main-jar", Jar.Sort.CLASSES.getFile());
                jpackage.setProperty("--main-class", main);
                jpackage.store(processFolder.resolve("jpackage.properties"));
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
