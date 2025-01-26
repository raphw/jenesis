package build.buildbuddy.test.project;

import build.buildbuddy.*;
import build.buildbuddy.project.MultiProjectDependencies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class MultiProjectDependenciesTest {

    @TempDir
    private Path root, target;
    private Path previous, next, supplement, module, dependency;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        module = Files.createDirectory(root.resolve("module"));
        dependency = Files.createDirectory(root.resolve("dependency"));
    }

    @Test
    public void can_assign_coordinate_target_dependencies() throws IOException, NoSuchAlgorithmException {
        Properties dependencies = new Properties();
        dependencies.setProperty("baz", "");
        try (Writer writer = Files.newBufferedWriter(module.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.store(writer, null);
        }
        Files.writeString(target, "qux");
        Properties coordinates = new Properties();
        coordinates.setProperty("baz", target.toString());
        try (Writer writer = Files.newBufferedWriter(dependency.resolve(BuildStep.COORDINATES))) {
            coordinates.store(writer, null);
        }
        BuildStepResult result = new MultiProjectDependencies("SHA256", "foo"::equals).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED)),
                                "bar", new BuildStepArgument(
                                        dependency,
                                        Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.DEPENDENCIES))) {
            properties.load(reader);
        }
        assertThat(properties.stringPropertyNames()).containsExactly("baz");
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA256")
                .digest("qux".getBytes())));
    }
}
