package build.buildbuddy.test.module;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorCallback;
import build.buildbuddy.BuildStep;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.maven.MavenDefaultRepository;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.module.ModularProject;
import build.buildbuddy.project.JavaModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class ModularProjectTest {

    @TempDir
    private Path project, build;

    @Test
    public void can_resolve_module() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("module", new ModularProject("module", project, _ -> true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("module/module-/sources", "module/module-/module");
        assertThat(results.get("module/module-/sources").resolve(BuildStep.SOURCES + "module-info.java")).exists();
        Path module = results.get("module/module-/module");
        assertThat(module.resolve(BuildStep.COORDINATES)).exists();
        Properties coordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(module.resolve(BuildStep.COORDINATES))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsOnlyKeys("module/foo");
        assertThat(coordinates.getProperty("module/foo")).isEmpty();
        assertThat(module.resolve(BuildStep.DEPENDENCIES)).exists();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(module.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies).containsOnlyKeys("module/bar");
        assertThat(dependencies.getProperty("module/bar")).isEmpty();
    }

    @Test
    public void can_resolve_multi_module() throws IOException {
        Path foo = Files.createDirectory(project.resolve("foo"));
        Files.writeString(foo.resolve("module-info.java"), """
                module foo { }
                """);
        Files.writeString(Files.createDirectories(foo.resolve("foo")).resolve("Foo.java"), """
                package foo;
                public class Foo { }
                """);
        Path bar = Files.createDirectory(project.resolve("bar"));
        Files.writeString(bar.resolve("module-info.java"), """
                module bar {
                  requires foo;
                }
                """);
        Files.writeString(Files.createDirectories(bar.resolve("bar")).resolve("Bar.java"), """
                package bar;
                import foo.Foo;
                public class Bar extends Foo { }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("modules", ModularProject.make(project,
                "module",
                _ -> true,
                "SHA256",
                Map.of(),
                Map.of(),
                (name, dependencies) -> {
                    return (buildExecutor, inherited) -> {
                        buildExecutor.addModule("java",
                                new JavaModule(),
                                Stream.concat(
                                                Stream.of("../dependencies/artifacts"),
                                                inherited.sequencedKeySet().stream().filter(identity -> identity.startsWith("../../../")))
                                        .collect(Collectors.toCollection(LinkedHashSet::new)));
                    };
                }));
        executor.execute();
    }
}
