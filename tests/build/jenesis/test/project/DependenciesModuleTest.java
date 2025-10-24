package build.jenesis.test.project;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.HashDigestFunction;
import build.jenesis.Resolver;
import build.jenesis.project.DependenciesModule;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class DependenciesModuleTest {

    @TempDir
    private Path input, root;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
    }

    @Test
    public void can_resolve_dependencies() throws IOException {
        Properties dependencies = new Properties();
        dependencies.setProperty("foo/bar", "");
        try (Writer writer = Files.newBufferedWriter(input.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.store(writer, null);
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new DependenciesModule(
                Map.of("foo", (_, coordinate) -> Optional.of(() -> new ByteArrayInputStream(
                        coordinate.getBytes(StandardCharsets.UTF_8)))),
                Map.of("foo", Resolver.identity())), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/resolved", "output/artifacts");
        Properties resolved = new Properties();
        try (Reader reader = Files.newBufferedReader(steps.get("output/resolved").resolve(BuildStep.DEPENDENCIES))) {
            resolved.load(reader);
        }
        assertThat(resolved.stringPropertyNames()).containsExactly("foo/bar");
        assertThat(resolved.getProperty("foo/bar")).isEqualTo("");
        assertThat(steps.get("output/artifacts")
                .resolve(BuildStep.ARTIFACTS)
                .resolve("foo-bar.jar")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_dependencies_with_checksums() throws IOException, NoSuchAlgorithmException {
        Properties dependencies = new Properties();
        dependencies.setProperty("foo/bar", "");
        try (Writer writer = Files.newBufferedWriter(input.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.store(writer, null);
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new DependenciesModule(
                Map.of("foo", (_, coordinate) -> Optional.of(() -> new ByteArrayInputStream(
                        coordinate.getBytes(StandardCharsets.UTF_8)))),
                Map.of("foo", Resolver.identity())).computeChecksums("SHA256"), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/prepared", "output/resolved", "output/artifacts");
        Properties resolved = new Properties();
        try (Reader reader = Files.newBufferedReader(steps.get("output/resolved").resolve(BuildStep.DEPENDENCIES))) {
            resolved.load(reader);
        }
        assertThat(resolved.stringPropertyNames()).containsExactly("foo/bar");
        assertThat(resolved.getProperty("foo/bar")).isEqualTo("SHA256/" + HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA256")
                .digest("bar".getBytes(StandardCharsets.UTF_8))));
        assertThat(steps.get("output/artifacts")
                .resolve(BuildStep.ARTIFACTS)
                .resolve("foo-bar.jar")).content().isEqualTo("bar");
    }
}
