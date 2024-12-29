package build.buildbuddy.test.step;

import build.buildbuddy.*;
import build.buildbuddy.step.Define;
import build.buildbuddy.step.FlattenDependencies;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class DefineTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, definition;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        definition = Files.createDirectory(root.resolve("definition"));
    }

    @Test
    public void can_resolve_definition() throws IOException {
        Files.writeString(definition.resolve("name"), "bar");
        Files.writeString(definition.resolve("dependencies"), """
                qux=baz
                """);
        BuildStepResult result = new Define(Map.of("foo", folder -> {
            SequencedMap<String, String> dependencies = new LinkedHashMap<>();
            for (String line : Files.readAllLines(folder.resolve("dependencies"))) {
                String[] elements = line.split("=", 2);
                dependencies.put(elements[0], elements[1]);
            }
            return Optional.of(new Identification(Files.readString(folder.resolve("name")), dependencies));
        })).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of(
                "definition",
                new BuildStepArgument(definition, Map.of(
                        Path.of("name"), ChecksumStatus.ADDED,
                        Path.of("dependencies"), ChecksumStatus.ADDED)))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties definition = new Properties(), dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Define.DEFINITION + "definition.properties"))) {
            definition.load(reader);
        }
        assertThat(definition.stringPropertyNames()).containsExactly("foo/bar");
        try (Reader reader = Files.newBufferedReader(next.resolve(FlattenDependencies.DEPENDENCIES + "dependencies.properties"))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactly("foo/qux");
        assertThat(dependencies.getProperty("foo/qux")).isEqualTo("baz");
    }
}
