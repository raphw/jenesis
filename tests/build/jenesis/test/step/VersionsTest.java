package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Versions;

import static org.assertj.core.api.Assertions.assertThat;

public class VersionsTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, classesInput, requiresInput;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        classesInput = Files.createDirectory(root.resolve("classes-input"));
        requiresInput = Files.createDirectory(root.resolve("requires-input"));
    }

    @Test
    public void stamps_version_on_matching_requires() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"));
        writeRequires(Map.of("module/bar/1.2.3", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.requires())
                .filteredOn(require -> require.name().equals("bar"))
                .singleElement()
                .extracting(require -> require.rawCompiledVersion().orElse(null))
                .isEqualTo("1.2.3");
    }

    @Test
    public void leaves_unmatched_requires_unchanged() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"), require("qux"));
        writeRequires(Map.of("module/bar/1.0", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.requires())
                .filteredOn(require -> require.name().equals("qux"))
                .singleElement()
                .extracting(require -> require.rawCompiledVersion().isPresent())
                .isEqualTo(false);
    }

    @Test
    public void overwrites_existing_compiled_version_when_present_in_versions() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("foo"),
                builder -> {
                    builder.requires(ModuleDesc.of("java.base"), 0, null);
                    builder.requires(ModuleDesc.of("bar"), 0, "0.1");
                })));
        Path classesDir = Files.createDirectory(classesInput.resolve(BuildStep.CLASSES));
        Files.write(classesDir.resolve("module-info.class"), buffer.toByteArray());
        writeRequires(Map.of("module/bar/2.0", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.requires())
                .filteredOn(require -> require.name().equals("bar"))
                .singleElement()
                .extracting(require -> require.rawCompiledVersion().orElse(null))
                .isEqualTo("2.0");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void non_module_info_files_are_hardlinked() throws IOException {
        Path classesDir = Files.createDirectory(classesInput.resolve(BuildStep.CLASSES));
        Path subDir = Files.createDirectories(classesDir.resolve("foo/sub"));
        Path classFile = subDir.resolve("Sample.class");
        Files.write(classFile, new byte[] { 0x01, 0x02, 0x03 });
        Path other = classesDir.resolve("META-INF").resolve("MANIFEST.MF");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "Manifest-Version: 1.0\n");
        writeModuleInfo("foo", null, false);
        writeRequires(Map.of());
        runStep();
        Path outputClasses = next.resolve(BuildStep.CLASSES);
        Path stampedClass = outputClasses.resolve("foo/sub/Sample.class");
        Path stampedManifest = outputClasses.resolve("META-INF/MANIFEST.MF");
        assertThat(stampedClass).exists().hasBinaryContent(new byte[] { 0x01, 0x02, 0x03 });
        assertThat(stampedManifest).exists();
        assertThat(Files.getAttribute(stampedClass, "unix:nlink")).isEqualTo(2);
        assertThat(Files.getAttribute(stampedManifest, "unix:nlink")).isEqualTo(2);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void module_info_is_rewritten_not_hardlinked() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"));
        writeRequires(Map.of("module/bar/1.0", ""));
        runStep();
        Path stamped = next.resolve(BuildStep.CLASSES).resolve("module-info.class");
        assertThat(Files.getAttribute(stamped, "unix:nlink")).isEqualTo(1);
    }

    @Test
    public void preserves_module_version() throws IOException {
        writeModuleInfo("foo", "9.0", false, require("bar"));
        writeRequires(Map.of("module/bar/1.0", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.rawVersion()).contains("9.0");
    }

    @Test
    public void preserves_open_module_flag() throws IOException {
        writeModuleInfo("foo", null, true, require("bar"));
        writeRequires(Map.of("module/bar/1.0", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.isOpen()).isTrue();
    }

    @Test
    public void preserves_exports_opens_uses_provides() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("foo"),
                builder -> {
                    builder.requires(ModuleDesc.of("java.base"), 0, null);
                    builder.requires(ModuleDesc.of("bar"), 0, null);
                    builder.exports(ModuleExportInfo.of(PackageDesc.of("foo.api"), 0));
                    builder.opens(ModuleOpenInfo.of(PackageDesc.of("foo.internal"), 0));
                    builder.uses(ClassDesc.of("foo.api.Service"));
                    builder.provides(ModuleProvideInfo.of(
                            ClassDesc.of("foo.api.Service"),
                            ClassDesc.of("foo.internal.ServiceImpl")));
                })));
        Path classesDir = Files.createDirectory(classesInput.resolve(BuildStep.CLASSES));
        Files.write(classesDir.resolve("module-info.class"), buffer.toByteArray());
        writeRequires(Map.of("module/bar/1.0", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.exports()).extracting(ModuleDescriptor.Exports::source)
                .contains("foo.api");
        assertThat(descriptor.opens()).extracting(ModuleDescriptor.Opens::source)
                .contains("foo.internal");
        assertThat(descriptor.uses()).contains("foo.api.Service");
        assertThat(descriptor.provides()).singleElement()
                .satisfies(provides -> {
                    assertThat(provides.service()).isEqualTo("foo.api.Service");
                    assertThat(provides.providers()).containsExactly("foo.internal.ServiceImpl");
                });
    }

    @Test
    public void merges_versions_from_multiple_requires_files() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"), require("qux"));
        Path otherRequires = Files.createDirectory(root.resolve("other-requires"));
        SequencedProperties first = new SequencedProperties();
        first.setProperty("module/bar/1.0", "dependencies/bar.jar");
        first.store(requiresInput.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties second = new SequencedProperties();
        second.setProperty("module/qux/2.0", "dependencies/qux.jar");
        second.store(otherRequires.resolve(BuildStep.DEPENDENCIES));
        BuildStepResult result = new Versions().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "classes", argumentFor(classesInput),
                                "requires-1", argumentFor(requiresInput),
                                "requires-2", argumentFor(otherRequires))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        ModuleDescriptor descriptor = readModuleInfo();
        Map<String, String> requires = descriptor.requires().stream()
                .filter(r -> r.compiledVersion().isPresent())
                .collect(Collectors.toMap(
                        ModuleDescriptor.Requires::name,
                        r -> r.rawCompiledVersion().get()));
        assertThat(requires).containsOnly(
                Map.entry("bar", "1.0"),
                Map.entry("qux", "2.0"));
    }

    @Test
    public void ignores_multi_segment_coordinates() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"));
        writeRequires(Map.of("maven/group/artifact/jar/5.0", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.requires())
                .filteredOn(require -> require.name().equals("bar"))
                .singleElement()
                .extracting(require -> require.rawCompiledVersion().isPresent())
                .isEqualTo(false);
    }

    @Test
    public void ignores_unversioned_coordinates() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"));
        writeRequires(Map.of("module/bar", ""));
        runStep();
        ModuleDescriptor descriptor = readModuleInfo();
        assertThat(descriptor.requires())
                .filteredOn(require -> require.name().equals("bar"))
                .singleElement()
                .extracting(require -> require.rawCompiledVersion().isPresent())
                .isEqualTo(false);
    }

    @Test
    public void empty_inputs_produce_empty_output() throws IOException {
        Files.createDirectory(classesInput.resolve(BuildStep.CLASSES));
        writeRequires(Map.of());
        runStep();
        assertThat(next.resolve(BuildStep.CLASSES)).exists().isEmptyDirectory();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void reuses_prior_module_info_when_requires_and_module_info_retained() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"));
        writeRequires(Map.of("module/bar/1.0", ""));
        Path priorClasses = Files.createDirectories(previous.resolve(BuildStep.CLASSES));
        byte[] sentinel = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        Files.write(priorClasses.resolve("module-info.class"), sentinel);
        BuildStepResult result = new Versions().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "classes", new BuildStepArgument(
                                        classesInput,
                                        Map.of(Path.of(BuildStep.CLASSES + "module-info.class"),
                                                Checksum.of(ChecksumStatus.RETAINED))),
                                "requires", new BuildStepArgument(
                                        requiresInput,
                                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.RETAINED))))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        Path output = next.resolve(BuildStep.CLASSES).resolve("module-info.class");
        assertThat(output).hasBinaryContent(sentinel);
        assertThat(Files.getAttribute(output, "unix:nlink")).isEqualTo(2);
    }

    @Test
    public void restamps_module_info_when_requires_changed() throws IOException {
        writeModuleInfo("foo", null, false, require("bar"));
        writeRequires(Map.of("module/bar/1.0", ""));
        Path priorClasses = Files.createDirectories(previous.resolve(BuildStep.CLASSES));
        Files.write(priorClasses.resolve("module-info.class"), new byte[] { 0x01, 0x02 });
        BuildStepResult result = new Versions().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "classes", new BuildStepArgument(
                                        classesInput,
                                        Map.of(Path.of(BuildStep.CLASSES + "module-info.class"),
                                                Checksum.of(ChecksumStatus.RETAINED))),
                                "requires", new BuildStepArgument(
                                        requiresInput,
                                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ALTERED))))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        Path output = next.resolve(BuildStep.CLASSES).resolve("module-info.class");
        ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(output));
        assertThat(descriptor.requires())
                .filteredOn(r -> r.name().equals("bar"))
                .singleElement()
                .extracting(r -> r.rawCompiledVersion().orElse(null))
                .isEqualTo("1.0");
    }

    @Test
    public void preserves_nested_directory_structure() throws IOException {
        Path classesDir = Files.createDirectory(classesInput.resolve(BuildStep.CLASSES));
        Path nested = Files.createDirectories(classesDir.resolve("a/b/c"));
        Files.write(nested.resolve("Deep.class"), new byte[] { 0x42 });
        writeModuleInfo("foo", null, false);
        writeRequires(Map.of());
        runStep();
        Path output = next.resolve(BuildStep.CLASSES);
        assertThat(output.resolve("a/b/c/Deep.class")).exists().hasBinaryContent(new byte[] { 0x42 });
        assertThat(output.resolve("module-info.class")).exists();
    }

    @Test
    public void forwards_top_level_manifest() throws IOException {
        Path classesDir = Files.createDirectory(classesInput.resolve(BuildStep.CLASSES));
        Files.write(classesDir.resolve("Sample.class"), new byte[] { 0x01 });
        Files.writeString(classesInput.resolve("manifest.mf"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n");
        writeRequires(Map.of());
        runStep();
        assertThat(next.resolve("manifest.mf"))
                .exists()
                .content().contains("Multi-Release: true");
    }

    private static ModuleRequireInfo require(String name) {
        return ModuleRequireInfo.of(ModuleDesc.of(name), 0, null);
    }

    private void writeModuleInfo(String module, String version, boolean open, ModuleRequireInfo... requires) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(module),
                builder -> {
                    builder.moduleFlags(open ? AccessFlag.OPEN.mask() : 0);
                    if (version != null) {
                        builder.moduleVersion(version);
                    }
                    builder.requires(ModuleDesc.of("java.base"), 0, null);
                    for (ModuleRequireInfo require : requires) {
                        builder.requires(require);
                    }
                })));
        Path classesDir = classesInput.resolve(BuildStep.CLASSES);
        if (!Files.exists(classesDir)) {
            Files.createDirectory(classesDir);
        }
        Files.write(classesDir.resolve("module-info.class"), buffer.toByteArray());
    }

    private void writeRequires(Map<String, String> entries) throws IOException {
        SequencedProperties properties = new SequencedProperties();
        entries.forEach((key, _) -> properties.setProperty(key, "dependencies/x.jar"));
        properties.store(requiresInput.resolve(BuildStep.DEPENDENCIES));
    }

    private void runStep() throws IOException {
        BuildStepResult result = new Versions().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "classes", argumentFor(classesInput),
                                "requires", argumentFor(requiresInput))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
    }

    private static BuildStepArgument argumentFor(Path folder) {
        return new BuildStepArgument(folder, Map.of(Path.of("."), Checksum.of(ChecksumStatus.ADDED)));
    }

    private ModuleDescriptor readModuleInfo() throws IOException {
        return ModuleDescriptor.read(Files.newInputStream(
                next.resolve(BuildStep.CLASSES).resolve("module-info.class")));
    }
}
