package build.jenesis.test.step;

import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Bind;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class BindTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, original;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        original = Files.createDirectory(root.resolve("original"));
    }

    @Test
    public void can_link_files() throws IOException {
        Files.writeString(original.resolve("file"), "foo");
        Files.writeString(Files.createDirectories(original.resolve("folder/sub")).resolve("file"), "bar");
        BuildStepResult result = new Bind(
                Map.of(
                        Path.of("file"), Path.of("other/copied"),
                        Path.of("folder"), Path.of("other"))).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("original", new BuildStepArgument(
                        original,
                        Map.of(Path.of("file"), ChecksumStatus.ADDED,
                                Path.of("folder/sub/file"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("other/copied")).content().isEqualTo("foo");
        assertThat(next.resolve("other/sub/file")).content().isEqualTo("bar");
    }
}