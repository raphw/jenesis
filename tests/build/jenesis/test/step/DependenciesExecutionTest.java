package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;

import static org.assertj.core.api.Assertions.assertThat;

public class DependenciesExecutionTest {

    @TempDir
    private Path input, root;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }

    @Test
    public void can_resolve_dependencies() throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/foo/bar", "");
        dependencies.store(input.resolve(BuildStep.REQUIRES));
        buildExecutor.addSource("input", input);
        buildExecutor.addStep("output", new Dependencies(
                Map.of("foo", (_, coordinate) -> Optional.of(() -> new ByteArrayInputStream(
                        coordinate.getBytes(StandardCharsets.UTF_8)))),
                Map.of("foo", Resolver.identity())), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKey("output");
        SequencedProperties resolved = SequencedProperties.ofFiles(steps.get("output").resolve(BuildStep.DEPENDENCIES));
        assertThat(resolved.stringPropertyNames()).containsExactly("main/compile/foo/bar");
        assertThat(resolved.getProperty("main/compile/foo/bar")).doesNotContain(" ");
        assertThat(steps.get("output")
                .resolve(resolved.getProperty("main/compile/foo/bar"))).content().isEqualTo("bar");
    }

}
