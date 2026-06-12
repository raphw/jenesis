package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.SequencedProperties;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Javac;

import static org.assertj.core.api.Assertions.assertThat;

public class JavacTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    private Path root;
    private Path previous, next, supplement, sources;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        sources = Files.createDirectory(root.resolve("sources"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javac(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
    }

    @Test
    public void writeRelease_writes_process_javac_properties_with_release_flag() throws IOException {
        Path folder = Files.createDirectory(root.resolve("write-release"));
        Javac.writeRelease(folder, "21");
        Path file = folder.resolve("process/javac.properties");
        assertThat(file).exists();
        SequencedProperties properties = SequencedProperties.ofFiles(file);
        assertThat(properties).containsEntry("--release", "21");
    }

    @Test
    public void writeRelease_null_or_empty_writes_nothing() throws IOException {
        Path folder = Files.createDirectory(root.resolve("write-release-empty"));
        Javac.writeRelease(folder, null);
        Javac.writeRelease(folder, "");
        assertThat(folder.resolve("process")).doesNotExist();
    }

    @Test
    public void shouldRun_skips_when_only_irrelevant_files_changed() {
        BuildStepArgument argument = new BuildStepArgument(sources, Map.of(
                Path.of("sources/sample/note.txt"), Checksum.ADDED,
                Path.of("sources/sample/Sample.kt"), Checksum.ADDED,
                Path.of("metadata.properties"), Checksum.ADDED));
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("input", argument);
        assertThat(new Javac(ProcessHandler.Factory.TOOL).shouldRun(arguments)).isFalse();
    }

    @Test
    public void shouldRun_fires_when_a_java_source_changed() {
        BuildStepArgument argument = new BuildStepArgument(sources, Map.of(
                Path.of("sources/sample/Sample.java"), Checksum.ADDED));
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("input", argument);
        assertThat(new Javac(ProcessHandler.Factory.TOOL).shouldRun(arguments)).isTrue();
    }

    @Test
    public void shouldRun_fires_when_upstream_classpath_or_dependencies_changed() {
        for (Path changed : List.of(Path.of("classes/x.jar"), Path.of("artifacts/x.jar"), Path.of(BuildStep.DEPENDENCIES))) {
            BuildStepArgument argument = new BuildStepArgument(sources, Map.of(
                    changed, Checksum.ADDED));
            SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
            arguments.put("input", argument);
            assertThat(new Javac(ProcessHandler.Factory.TOOL).shouldRun(arguments))
                    .as("change to " + changed + " triggers Javac")
                    .isTrue();
        }
    }

    @Test
    public void shouldRun_fires_when_javac_properties_changed() {
        BuildStepArgument argument = new BuildStepArgument(sources, Map.of(
                Path.of("process/javac.properties"), Checksum.ADDED));
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("input", argument);
        assertThat(new Javac(ProcessHandler.Factory.TOOL).shouldRun(arguments)).isTrue();
    }

    @Test
    public void shouldRun_ignores_retained_files() {
        BuildStepArgument argument = new BuildStepArgument(sources, Map.of(
                Path.of("sources/sample/Sample.java"), Checksum.RETAINED,
                Path.of("classes/Other.class"), Checksum.RETAINED));
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("input", argument);
        assertThat(new Javac(ProcessHandler.Factory.TOOL).shouldRun(arguments)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void release_in_process_javac_properties_is_forwarded_to_javac(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        Javac.writeRelease(sources, "21");
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED,
                                Path.of("process/javac.properties"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void stamps_module_version_when_javac_properties_contains_module_version(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("module-info.java"))) {
            writer.append("module sample { }");
            writer.newLine();
        }
        SequencedProperties javac = new SequencedProperties();
        javac.setProperty("--module-version", "1.2.3");
        Path processFolder = Files.createDirectories(sources.resolve("process"));
        javac.store(processFolder.resolve("javac.properties"));
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(this.previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/module-info.java"), Checksum.ADDED,
                                Path.of("process/javac.properties"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path moduleInfo = next.resolve(Javac.CLASSES + "module-info.class");
        assertThat(moduleInfo).isNotEmptyFile();
        ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
        assertThat(descriptor.rawVersion()).contains("1.2.3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void does_not_stamp_module_version_when_javac_properties_absent(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("module-info.java"))) {
            writer.append("module sample { }");
            writer.newLine();
        }
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(this.previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/module-info.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path moduleInfo = next.resolve(Javac.CLASSES + "module-info.class");
        assertThat(moduleInfo).isNotEmptyFile();
        ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
        assertThat(descriptor.rawVersion()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void compiles_versioned_sources_with_release_under_meta_inf_versions(boolean process) throws IOException {
        Path main = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(main.resolve("Sample.java"), """
                package sample;
                public class Sample { public String greet() { return "base"; } }
                """);
        Path versioned = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/versions/21/sample"));
        Files.writeString(versioned.resolve("Sample.java"), """
                package sample;
                public class Sample { public String greet() { return "21"; } }
                """);
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED,
                                Path.of("sources/META-INF/versions/21/sample/Sample.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path mainClass = next.resolve(Javac.CLASSES + "sample/Sample.class");
        Path overlayClass = next.resolve(Javac.CLASSES + "META-INF/versions/21/sample/Sample.class");
        assertThat(Files.readString(mainClass, StandardCharsets.ISO_8859_1))
                .contains("base")
                .doesNotContain("21");
        assertThat(Files.readString(overlayClass, StandardCharsets.ISO_8859_1))
                .contains("21")
                .doesNotContain("base");
        assertThat(next.resolve("manifest.mf")).content()
                .contains("Multi-Release: true");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void versioned_compilation_mixes_overlay_and_additional_files(boolean process) throws IOException {
        Path main = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(main.resolve("Sample.java"), """
                package sample;
                public class Sample { public String greet() { return "base"; } }
                """);
        Files.writeString(main.resolve("Helper.java"), """
                package sample;
                public class Helper { public static String name() { return "helper"; } }
                """);
        Path versioned = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/versions/21/sample"));
        Files.writeString(versioned.resolve("Sample.java"), """
                package sample;
                public class Sample { public String greet() { return new Caller().forward(); } }
                """);
        Files.writeString(versioned.resolve("Caller.java"), """
                package sample;
                public class Caller { public String forward() { return Helper.name() + "/21"; } }
                """);
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED,
                                Path.of("sources/sample/Helper.java"), Checksum.ADDED,
                                Path.of("sources/META-INF/versions/21/sample/Sample.java"), Checksum.ADDED,
                                Path.of("sources/META-INF/versions/21/sample/Caller.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "sample/Helper.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "sample/Caller.class"))
                .as("Caller is only in the overlay and must not be compiled into the main classes")
                .doesNotExist();
        assertThat(next.resolve(Javac.CLASSES + "META-INF/versions/21/sample/Sample.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "META-INF/versions/21/sample/Caller.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "META-INF/versions/21/sample/Helper.class"))
                .as("Helper is not overlaid and must not be re-emitted into the versioned tree")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void versioned_compilation_can_reference_main_class_via_classpath(boolean process) throws IOException {
        Path main = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(main.resolve("Helper.java"), """
                package sample;
                public class Helper { public static String name() { return "helper"; } }
                """);
        Path versioned = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/versions/17/sample"));
        Files.writeString(versioned.resolve("Caller.java"), """
                package sample;
                public class Caller { public String value() { return Helper.name(); } }
                """);
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Helper.java"), Checksum.ADDED,
                                Path.of("sources/META-INF/versions/17/sample/Caller.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Helper.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "META-INF/versions/17/sample/Caller.class")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void versioned_compilation_patches_module_for_modular_main(boolean process) throws IOException {
        Path main = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sources.resolve(BuildStep.SOURCES).resolve("module-info.java"),
                "module sample { exports sample; }\n");
        Files.writeString(main.resolve("Helper.java"), """
                package sample;
                public class Helper { public static String name() { return "helper"; } }
                """);
        Path versioned = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/versions/21/sample"));
        Files.writeString(versioned.resolve("Caller.java"), """
                package sample;
                public class Caller { public String value() { return Helper.name(); } }
                """);
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/module-info.java"), Checksum.ADDED,
                                Path.of("sources/sample/Helper.java"), Checksum.ADDED,
                                Path.of("sources/META-INF/versions/21/sample/Caller.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "module-info.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "META-INF/versions/21/sample/Caller.class")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void multi_release_manifest_is_not_emitted_when_predecessor_already_supplies_one(boolean process) throws IOException {
        Path main = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(main.resolve("Sample.java"), "package sample; public class Sample { }\n");
        Path versioned = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/versions/21/sample"));
        Files.writeString(versioned.resolve("Sample.java"), "package sample; public class Sample { }\n");
        Files.writeString(sources.resolve("manifest.mf"),
                "Manifest-Version: 1.0\r\nMulti-Release: true\r\nMain-Class: sample.Sample\r\n");
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED,
                                Path.of("sources/META-INF/versions/21/sample/Sample.java"), Checksum.ADDED,
                                Path.of("manifest.mf"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("manifest.mf"))
                .as("Javac defers to the predecessor's manifest when it already declares Multi-Release")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void multi_release_manifest_is_still_emitted_when_predecessor_manifest_lacks_multi_release(boolean process) throws IOException {
        Path main = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(main.resolve("Sample.java"), "package sample; public class Sample { }\n");
        Path versioned = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/versions/21/sample"));
        Files.writeString(versioned.resolve("Sample.java"), "package sample; public class Sample { }\n");
        Files.writeString(sources.resolve("manifest.mf"),
                "Manifest-Version: 1.0\r\nMain-Class: sample.Sample\r\n");
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED,
                                Path.of("sources/META-INF/versions/21/sample/Sample.java"), Checksum.ADDED,
                                Path.of("manifest.mf"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("manifest.mf")).content().contains("Multi-Release: true");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void plain_compilation_does_not_emit_manifest(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(folder.resolve("Sample.java"), "package sample; public class Sample { }\n");
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("manifest.mf")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void patches_sibling_classes_into_the_module_so_a_foreign_package_can_be_exported(boolean process) throws IOException {
        Path siblingSource = Files.createDirectories(root.resolve("gen").resolve("pure")).resolve("Generated.java");
        Files.writeString(siblingSource, """
                package pure;
                public class Generated { public String value() { return "generated"; } }
                """);
        Path siblingClasses = Files.createDirectories(root.resolve("sibling").resolve(Javac.CLASSES));
        ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
        int code = javac.run(System.out, System.err, "-d", siblingClasses.toString(), siblingSource.toString());
        assertThat(code).isZero();
        Files.writeString(
                Files.createDirectories(sources.resolve(BuildStep.SOURCES)).resolve("module-info.java"),
                "module sample { exports pure; }\n");

        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("sibling", new BuildStepArgument(
                root.resolve("sibling"),
                Map.of(Path.of(Javac.CLASSES + "pure/Generated.class"), Checksum.ADDED)));
        arguments.put("sources", new BuildStepArgument(
                sources,
                Map.of(Path.of("sources/module-info.java"), Checksum.ADDED)));
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                arguments).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path moduleInfo = next.resolve(Javac.CLASSES + "module-info.class");
        assertThat(moduleInfo).isNotEmptyFile();
        ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
        assertThat(descriptor.exports().stream().map(ModuleDescriptor.Exports::source))
                .as("javac patched the sibling-language classes into the module, exporting a package with no Java source")
                .contains("pure");
        assertThat(next.resolve(Javac.CLASSES + "pure/Generated.class"))
                .as("patched classes are referenced for compilation, not re-emitted into javac's own output")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void runs_annotation_processor_from_processor_path(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(folder.resolve("Sample.java"), "package sample; public class Sample { }\n");
        Path processorRoot = Files.createDirectories(root.resolve("processor"));
        Path jar = buildProcessorJar(Files.createDirectories(root.resolve("procbuild")), "one", "One", null);
        Files.copy(jar, Files.createDirectories(processorRoot.resolve("resolved")).resolve("processor.jar"));
        SequencedProperties index = new SequencedProperties();
        index.setProperty("plugin/plugin/maven/processor", "resolved/processor.jar");
        index.store(processorRoot.resolve(BuildStep.DEPENDENCIES));

        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("sources", new BuildStepArgument(sources,
                Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED)));
        arguments.put("processors/artifacts", new BuildStepArgument(processorRoot,
                Map.of(Path.of("resolved/processor.jar"), Checksum.ADDED)));
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL)
                .apply(Runnable::run, new BuildStepContext(previous, next, supplement), arguments)
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "gen/GeneratedOne.class"))
                .as("annotation processor discovered on the processor path generated and compiled a class")
                .isNotEmptyFile();
        String args = Files.readString(supplement.resolve("javac.args"));
        assertThat(args)
                .as("a non-modular compilation passes processors via --processor-path, not the compilation class path")
                .contains("--processor-path")
                .doesNotContain("--class-path");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void runs_modular_and_plain_processors_via_one_processor_module_path(boolean process) throws IOException {
        Files.createDirectories(sources.resolve(BuildStep.SOURCES));
        Files.writeString(sources.resolve(BuildStep.SOURCES).resolve("module-info.java"),
                "module sample { exports sample; }\n");
        Files.writeString(Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample")).resolve("Sample.java"),
                "package sample; public class Sample { }\n");
        Path processorRoot = Files.createDirectories(root.resolve("processor").resolve("resolved"));
        Files.copy(buildProcessorJar(Files.createDirectories(root.resolve("plain")), "plain", "Plain", null),
                processorRoot.resolve("plain.jar"));
        Files.copy(buildProcessorJar(Files.createDirectories(root.resolve("modular")), "modular", "Modular", "proc.modular"),
                processorRoot.resolve("modular.jar"));
        SequencedProperties index = new SequencedProperties();
        index.setProperty("plugin/plugin/maven/plain", "resolved/plain.jar");
        index.setProperty("plugin/plugin/maven/modular", "resolved/modular.jar");
        index.store(root.resolve("processor").resolve(BuildStep.DEPENDENCIES));

        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("sources", new BuildStepArgument(sources, Map.of(
                Path.of("sources/module-info.java"), Checksum.ADDED,
                Path.of("sources/sample/Sample.java"), Checksum.ADDED)));
        arguments.put("processors/artifacts", new BuildStepArgument(root.resolve("processor"), Map.of(
                Path.of("resolved/plain.jar"), Checksum.ADDED,
                Path.of("resolved/modular.jar"), Checksum.ADDED)));
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL)
                .apply(Runnable::run, new BuildStepContext(previous, next, supplement), arguments)
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "gen/GeneratedPlain.class"))
                .as("a plain (META-INF/services) processor runs from the processor module path as an automatic module")
                .isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "gen/GeneratedModular.class"))
                .as("a modular (module-info provides) processor runs from the processor module path")
                .isNotEmptyFile();
        String args = Files.readString(supplement.resolve("javac.args"));
        assertThat(args)
                .as("a modular compilation passes every processor via a single --processor-module-path")
                .contains("--processor-module-path")
                .doesNotContain("--processor-path\n");
    }

    private Path buildProcessorJar(Path dir, String pkg, String suffix, String moduleName) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("proc-classes"));
        List<String> files = new ArrayList<>();
        Path src = dir.resolve("Gen" + suffix + ".java");
        Files.writeString(src, """
                package %1$s;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import javax.tools.JavaFileObject;
                import java.io.Writer;
                import java.util.Set;
                @SupportedAnnotationTypes("*")
                public class Gen%2$s extends AbstractProcessor {
                    private boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                        if (!done && !roundEnv.getRootElements().isEmpty()) {
                            done = true;
                            try {
                                JavaFileObject file = processingEnv.getFiler().createSourceFile("gen.Generated%2$s");
                                try (Writer writer = file.openWriter()) {
                                    writer.write("package gen; public class Generated%2$s { }");
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return false;
                    }
                }
                """.formatted(pkg, suffix));
        files.add(src.toString());
        if (moduleName != null) {
            Path moduleInfo = dir.resolve("module-info.java");
            Files.writeString(moduleInfo, """
                    module %s {
                        requires java.compiler;
                        provides javax.annotation.processing.Processor with %s.Gen%s;
                    }
                    """.formatted(moduleName, pkg, suffix));
            files.add(moduleInfo.toString());
        }
        ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
        List<String> command = new ArrayList<>(List.of("-d", classes.toString()));
        command.addAll(files);
        assertThat(javac.run(System.out, System.err, command.toArray(String[]::new))).isZero();
        if (moduleName == null) {
            Files.writeString(
                    Files.createDirectories(classes.resolve("META-INF/services")).resolve("javax.annotation.processing.Processor"),
                    pkg + ".Gen" + suffix + "\n");
        }
        Path jar = dir.resolve("processor.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            List<Path> entries;
            try (Stream<Path> walk = Files.walk(classes)) {
                entries = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : entries) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javac_with_resources(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        Files.writeString(folder.resolve("foo"), "bar");
        Files.createDirectory(sources.resolve(BuildStep.SOURCES + "folder"));
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "sample/foo")).content().isEqualTo("bar");
        assertThat(next.resolve(Javac.CLASSES + "folder")).isDirectory();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void includeResources_false_skips_non_java_files(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(folder.resolve("Sample.java"), "package sample; public class Sample { }\n");
        Files.writeString(folder.resolve("app.properties"), "key=value");
        Files.writeString(folder.resolve("Other.kt"), "package sample\nclass Other\n");
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL)
                .includeResources(false)
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                                sources,
                                Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED,
                                        Path.of("sources/sample/app.properties"), Checksum.ADDED,
                                        Path.of("sources/sample/Other.kt"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "sample/app.properties"))
                .as("resources are excluded from output when includeResources(false)")
                .doesNotExist();
        assertThat(next.resolve(Javac.CLASSES + "sample/Other.kt"))
                .as("foreign-language sources are excluded from output when includeResources(false)")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void copies_meta_inf_resources_but_not_the_versions_overlay(boolean process) throws IOException {
        Path sample = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sample.resolve("Sample.java"), "package sample; public class Sample { }\n");
        Path config = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/native-image/app"));
        Files.writeString(config.resolve("reachability-metadata.json"), "[]");
        Path versioned = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "META-INF/versions/25"));
        Files.writeString(versioned.resolve("overlay.json"), "{}");
        BuildStepResult result = new Javac(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL)
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                                sources,
                                Map.of(Path.of("sources/sample/Sample.java"), Checksum.ADDED,
                                        Path.of("sources/META-INF/native-image/app/reachability-metadata.json"), Checksum.ADDED,
                                        Path.of("sources/META-INF/versions/25/overlay.json"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "META-INF/native-image/app/reachability-metadata.json"))
                .as("META-INF resources are copied into the classes output verbatim")
                .content().isEqualTo("[]");
        assertThat(next.resolve(Javac.CLASSES + "META-INF/versions/25/overlay.json"))
                .as("the META-INF/versions multi-release overlay is not copied as a plain resource")
                .doesNotExist();
    }
}
