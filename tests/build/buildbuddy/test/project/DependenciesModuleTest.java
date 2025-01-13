package build.buildbuddy.test.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildStep;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.Resolver;
import build.buildbuddy.project.DependenciesModule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class DependenciesModuleTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path input;
    private BuildExecutor buildExecutor;

    @Before
    public void setUp() throws Exception {
        input = temporaryFolder.newFolder("input").toPath();
        buildExecutor = BuildExecutor.of(
                temporaryFolder.newFolder("root").toPath(),
                new HashDigestFunction("MD5"));
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
