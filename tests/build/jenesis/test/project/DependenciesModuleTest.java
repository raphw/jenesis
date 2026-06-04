package build.jenesis.test.project;

import module java.base;
import build.jenesis.DependencyScope;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.DependenciesModule;

import static org.assertj.core.api.Assertions.assertThat;

public class DependenciesModuleTest {

    @TempDir
    private Path input, root;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }

    @Test
    public void can_resolve_dependencies() throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("foo/bar", "");
        dependencies.store(input.resolve(BuildStep.REQUIRES));
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new DependenciesModule(
                Map.of("foo", (_, coordinate) -> Optional.of(() -> new ByteArrayInputStream(
                        coordinate.getBytes(StandardCharsets.UTF_8)))),
                Map.of("foo", Resolver.identity()),
                DependencyScope.COMPILE), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/resolved", "output/artifacts");
        SequencedProperties resolved = SequencedProperties.ofFiles(steps.get("output/resolved").resolve(BuildStep.REQUIRES));
        assertThat(resolved.stringPropertyNames()).containsExactly("foo/bar");
        assertThat(resolved.getProperty("foo/bar")).isEqualTo("");
        assertThat(steps.get("output/artifacts")
                .resolve(BuildStep.DEPENDENCIES)
                .resolve("foo-bar.jar")).content().isEqualTo("bar");
    }

}
