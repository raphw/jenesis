package build.buildbuddy.test.module;

import build.buildbuddy.*;
import build.buildbuddy.module.ModularJarResolver;
import build.buildbuddy.module.ModularProject;
import build.buildbuddy.project.JavaModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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
        Files.writeString(Files.createDirectory(project.resolve("foo")).resolve("module-info.java"), """
                module foo {
                  exports foo;
                }
                """);
        Files.writeString(Files.createDirectories(project.resolve("foo/foo")).resolve("Foo.java"), """
                package foo;
                public class Foo { }
                """);
        Files.writeString(Files.createDirectory(project.resolve("bar")).resolve("module-info.java"), """
                module bar {
                  requires foo;
                }
                """);
        Files.writeString(Files.createDirectories(project.resolve("bar/bar")).resolve("Bar.java"), """
                package bar;
                import foo.Foo;
                public class Bar extends Foo { }
                """);
        BuildExecutor root = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        root.addModule("modules", ModularProject.make(project,
                "module",
                _ -> true,
                "SHA256",
                Map.of(),
                Map.of("module", new ModularJarResolver(false)),
                (name, dependencies) -> {
                    switch (name) {
                        case "module-foo" -> assertThat(dependencies).isEmpty();
                        case "module-bar" -> assertThat(dependencies).containsExactly("module-foo");
                        default -> fail("Unexpected module: " + name);
                    }
                    return (buildExecutor, inherited) -> {
                        switch (name) {
                            case "module-foo" -> assertThat(inherited).containsOnlyKeys(
                                    "../../../../identify/module-foo/module",
                                    "../../../../identify/module-foo/sources",
                                    "../dependencies/prepared",
                                    "../dependencies/resolved",
                                    "../dependencies/artifacts");
                            case "module-bar" -> assertThat(inherited).containsOnlyKeys(
                                    "../../../../identify/module-bar/module",
                                    "../../../../identify/module-bar/sources",
                                    "../dependencies/prepared",
                                    "../dependencies/resolved",
                                    "../dependencies/artifacts",
                                    "../../module-foo/prepare",
                                    "../../module-foo/dependencies/prepared",
                                    "../../module-foo/dependencies/resolved",
                                    "../../module-foo/dependencies/artifacts",
                                    "../../module-foo/build/java/classes",
                                    "../../module-foo/build/java/artifacts",
                                    "../../module-foo/assign");
                            default -> fail("Unexpected module: " + name);
                        }
                        buildExecutor.addModule("java",
                                new JavaModule(),
                                Stream.concat(
                                        Stream.of("../dependencies/artifacts"),
                                        inherited.sequencedKeySet().stream()
                                                .filter(identity -> identity.startsWith("../../../"))));
                    };
                }));
        SequencedMap<String, Path> results = root.execute(Runnable::run).toCompletableFuture().join();
        Properties foo = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("modules/build/module/module-foo/assign")
                .resolve(BuildStep.COORDINATES))) {
            foo.load(reader);
        }
        assertThat(foo.stringPropertyNames()).containsExactly("module/foo");
        assertThat(foo.getProperty("module/foo")).isEqualTo(build
                .resolve("modules/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar")
                .toString());
        Properties bar = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("modules/build/module/module-bar/assign")
                .resolve(BuildStep.COORDINATES))) {
            bar.load(reader);
        }
        assertThat(bar.stringPropertyNames()).containsExactly("module/bar");
        assertThat(bar.getProperty("module/bar")).isEqualTo(build
                .resolve("modules/build/module/module-bar/build/java/artifacts/output/artifacts/classes.jar")
                .toString());
    }
}
