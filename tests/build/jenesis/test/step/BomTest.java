package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Bom;

import static org.assertj.core.api.Assertions.assertThat;

public class BomTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement;

    @BeforeEach
    public void setUp() throws IOException {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
    }

    @Test
    public void emits_module_and_maven_entries_group_less() throws IOException {
        Path manifests = manifests("demo.mod");
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/maven/org.slf4j/slf4j-api/2.0.16", "resolved/a.jar SHA-256/abc");
        dependencies.setProperty("main/compile/module/foo.bar/1.0", "resolved/b.jar SHA-256/def");
        Path artifacts = artifacts(dependencies);

        run(args("manifests", manifests, "artifacts", artifacts));

        SequencedProperties bom = read();
        assertThat(bom.getProperty("org.slf4j/slf4j-api")).isEqualTo("2.0.16 SHA-256/abc");
        assertThat(bom.getProperty("foo.bar")).isEqualTo("1.0 SHA-256/def");
    }

    @Test
    public void module_and_its_maven_alias_are_both_pinned() throws IOException {
        Path manifests = manifests("demo.mod");
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/module/org.slf4j/2.0.16", "resolved/module-org.slf4j-2.0.16.jar SHA-256/abc");
        dependencies.setProperty("main/compile/maven/org.slf4j/slf4j-api/2.0.16", "resolved/maven-org.slf4j-slf4j-api-2.0.16.jar SHA-256/abc");
        Path artifacts = artifacts(dependencies);

        run(args("manifests", manifests, "artifacts", artifacts));

        SequencedProperties bom = read();
        assertThat(bom.getProperty("org.slf4j")).isEqualTo("2.0.16 SHA-256/abc");
        assertThat(bom.getProperty("org.slf4j/slf4j-api")).isEqualTo("2.0.16 SHA-256/abc");
    }

    @Test
    public void computes_checksum_from_jar_when_absent() throws IOException {
        Path manifests = manifests("demo.mod");
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/runtime/maven/org.example/lib/1.0", "resolved/lib.jar");
        Path artifacts = artifacts(dependencies);
        Files.writeString(Files.createDirectory(artifacts.resolve("resolved")).resolve("lib.jar"), "content");

        run(args("manifests", manifests, "artifacts", artifacts));

        String expected = "1.0 " + new HashDigestFunction("SHA-256").encodedHash(artifacts.resolve("resolved/lib.jar"));
        assertThat(read().getProperty("org.example/lib")).isEqualTo(expected);
    }

    @Test
    public void folds_module_classifier_into_value() throws IOException {
        Path manifests = manifests("demo.mod");
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/module/foo.bar-natives/1.0", "resolved/c.jar SHA-256/ccc");
        Path artifacts = artifacts(dependencies);

        run(args("manifests", manifests, "artifacts", artifacts));

        assertThat(read().getProperty("foo.bar")).isEqualTo(":natives:1.0 SHA-256/ccc");
    }

    @Test
    public void skips_non_main_groups() throws IOException {
        Path manifests = manifests("demo.mod");
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/maven/org.slf4j/slf4j-api/2.0.16", "resolved/a.jar SHA-256/abc");
        dependencies.setProperty("kotlinc/compile/maven/org.jetbrains/annotations/24.0.0", "resolved/k.jar SHA-256/kkk");
        Path artifacts = artifacts(dependencies);

        run(args("manifests", manifests, "artifacts", artifacts));

        SequencedProperties bom = read();
        assertThat(bom.getProperty("org.slf4j/slf4j-api")).isEqualTo("2.0.16 SHA-256/abc");
        assertThat(bom.getProperty("org.jetbrains/annotations")).isNull();
    }

    @Test
    public void writes_no_file_without_a_module_name() throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/maven/org.slf4j/slf4j-api/2.0.16", "resolved/a.jar SHA-256/abc");
        Path artifacts = artifacts(dependencies);

        run(args("artifacts", artifacts));

        assertThat(next.resolve(Bom.BOM)).doesNotExist();
    }

    private Path manifests(String module) throws IOException {
        Path folder = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("module", module);
        properties.store(folder.resolve(BuildStep.MODULE));
        return folder;
    }

    private Path artifacts(SequencedProperties dependencies) throws IOException {
        Path folder = Files.createDirectory(root.resolve("artifacts"));
        dependencies.store(folder.resolve(BuildStep.DEPENDENCIES));
        return folder;
    }

    private SequencedProperties read() throws IOException {
        return SequencedProperties.ofFiles(next.resolve(Bom.BOM).resolve("bom-demo.mod.properties"));
    }

    private BuildStepResult run(SequencedMap<String, Path> argumentFolders) throws IOException {
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : argumentFolders.entrySet()) {
            arguments.put(entry.getKey(), new BuildStepArgument(entry.getValue(), Map.of()));
        }
        return new Bom(new HashDigestFunction("SHA-256")).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
    }

    private static SequencedMap<String, Path> args(Object... pairs) {
        SequencedMap<String, Path> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], (Path) pairs[index + 1]);
        }
        return map;
    }
}
