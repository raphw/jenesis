package build.buildbuddy.steps;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleDependenciesTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, modules, sources;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        modules = Files.createDirectory(root.resolve("modules"));
        sources = Files.createDirectory(root.resolve("sources"));
    }

    @Test
    public void can_resolve_dependencies() throws IOException, ExecutionException, InterruptedException {
        Files.writeString(Files.createDirectory(sources.resolve(Resolve.SOURCES)).resolve("module-info.java"), """
                module Sample {
                  requires foo;
                }
                """);
        Properties properties = new Properties();
        properties.setProperty("foo", "bar/qux");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(modules.resolve(ModuleDependencies.MODULES))
                .resolve("modules.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new ModuleDependencies(Function.identity()).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("modules", new BuildStepArgument(
                                modules,
                                Map.of(
                                        Path.of(ModuleDependencies.MODULES, "modules.properties"),
                                        ChecksumStatus.ADDED)),
                        "sources", new BuildStepArgument(
                                sources,
                                Map.of(
                                        Path.of(Resolve.SOURCES, "module-info.java"),
                                        ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(PropertyDependencies.DEPENDENCIES + "sources.properties"))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("bar/qux");
        assertThat(dependencies.getProperty("bar/qux")).isEmpty();
    }
}
