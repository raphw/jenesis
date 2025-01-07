package build.buildbuddy.test.step;

import build.buildbuddy.*;
import build.buildbuddy.step.Translate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class TranslateTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, dependencies;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
    }

    @Test
    public void can_translate_dependencies() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "foobar");
        properties.setProperty("bar/baz", "quxbaz");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Translate(Map.of(
                "foo",
                coordinate -> "translated/" + coordinate)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.DEPENDENCIES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("translated/qux", "bar/baz");
        assertThat(dependencies.getProperty("translated/qux")).isEqualTo("foobar");
        assertThat(dependencies.getProperty("bar/baz")).isEqualTo("quxbaz");
    }
}
