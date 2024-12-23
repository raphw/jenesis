package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path repository;

    private MavenRepository mavenRepository;

    @Before
    public void setUp() throws Exception {
        repository = temporaryFolder.newFolder("repository").toPath();
    }

    @Test
    public void can_fetch_dependency() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"))) {
            writer.write("foo");
        }
        Path dependency = temporaryFolder.newFile("dependency").toPath();
        try (
                InputStream inputStream = new MavenRepository(repository.toUri(), null, Map.of()).download("group",
                        "artifact",
                        "1",
                        "jar",
                        null).toInputStream();
                OutputStream outputStream = Files.newOutputStream(dependency)
        ) {
            inputStream.transferTo(outputStream);
        }
        assertThat(dependency).content().isEqualTo("foo");
    }

    @Test
    public void can_fetch_and_cache_dependency() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"))) {
            writer.write("foo");
        }
        Path local = temporaryFolder.newFolder("cache").toPath();
        Path dependency = temporaryFolder.newFile("dependency").toPath();
        try (
                InputStream inputStream = new MavenRepository(repository.toUri(), local, Map.of()).download("group",
                        "artifact",
                        "1",
                        "jar",
                        null).toInputStream();
                OutputStream outputStream = Files.newOutputStream(dependency)
        ) {
            inputStream.transferTo(outputStream);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
    }

    @Test
    public void can_fetch_and_validate_dependency() throws IOException, NoSuchAlgorithmException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"))) {
            writer.write("foo");
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest("foo".getBytes(StandardCharsets.UTF_8));
        try (OutputStream outputStream = Files.newOutputStream(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            outputStream.write(Base64.getEncoder().encode(hash));
        }
        Path local = temporaryFolder.newFolder("cache").toPath();
        Path dependency = temporaryFolder.newFile("dependency").toPath();
        try (
                InputStream inputStream = new MavenRepository(repository.toUri(),
                        local,
                        Map.of("MD5", repository.toUri())).download("group",
                        "artifact",
                        "1",
                        "jar",
                        null).toInputStream();
                OutputStream outputStream = Files.newOutputStream(dependency)
        ) {
            inputStream.transferTo(outputStream);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).content().isEqualTo(Base64.getEncoder().encodeToString(hash));
    }

    @Test
    public void can_fetch_and_fail_validate_dependency() throws IOException, NoSuchAlgorithmException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"))) {
            writer.write("foo");
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (OutputStream outputStream = Files.newOutputStream(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            outputStream.write(Base64.getEncoder().encode(digest.digest("bar".getBytes(StandardCharsets.UTF_8))));
        }
        Path local = temporaryFolder.newFolder("cache").toPath();
        MavenRepository repository = new MavenRepository(this.repository.toUri(),
                local,
                Map.of("MD5", this.repository.toUri()));
        assertThatThrownBy(() -> repository.download("group",
                "artifact",
                "1",
                "jar",
                null)).isInstanceOf(IOException.class);
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).doesNotExist();
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).doesNotExist();
    }

    @Test
    public void can_validate_cached_dependency() throws IOException, NoSuchAlgorithmException {
        Path local = temporaryFolder.newFolder("cache").toPath();
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"))) {
            writer.write("foo");
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest("foo".getBytes(StandardCharsets.UTF_8));
        try (OutputStream outputStream = Files.newOutputStream(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            outputStream.write(Base64.getEncoder().encode(hash));
        }
        Path dependency = temporaryFolder.newFile("dependency").toPath();
        try (
                InputStream inputStream = new MavenRepository(repository.toUri(),
                        local,
                        Map.of("MD5", repository.toUri())).download("group",
                        "artifact",
                        "1",
                        "jar",
                        null).toInputStream();
                OutputStream outputStream = Files.newOutputStream(dependency)
        ) {
            inputStream.transferTo(outputStream);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).content().isEqualTo(Base64.getEncoder().encodeToString(hash));
    }

    @Test
    public void can_validate_cached_dependency_with_cached_hash() throws IOException, NoSuchAlgorithmException {
        Path local = temporaryFolder.newFolder("cache").toPath();
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"))) {
            writer.write("foo");
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest("foo".getBytes(StandardCharsets.UTF_8));
        try (OutputStream outputStream = Files.newOutputStream(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            outputStream.write(Base64.getEncoder().encode(hash));
        }
        Path dependency = temporaryFolder.newFile("dependency").toPath();
        try (
                InputStream inputStream = new MavenRepository(repository.toUri(),
                        local,
                        Map.of("MD5", repository.toUri())).download("group",
                        "artifact",
                        "1",
                        "jar",
                        null).toInputStream();
                OutputStream outputStream = Files.newOutputStream(dependency)
        ) {
            inputStream.transferTo(outputStream);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).content().isEqualTo(Base64.getEncoder().encodeToString(hash));
    }

    @Test
    public void can_fail_validate_cached_dependency() throws IOException, NoSuchAlgorithmException {
        Path local = temporaryFolder.newFolder("cache").toPath();
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"))) {
            writer.write("foo");
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (OutputStream outputStream = Files.newOutputStream(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            outputStream.write(Base64.getEncoder().encode(digest.digest("bar".getBytes(StandardCharsets.UTF_8))));
        }
        MavenRepository repository = new MavenRepository(this.repository.toUri(),
                local,
                Map.of("MD5", this.repository.toUri()));
        assertThatThrownBy(() -> repository.download("group",
                "artifact",
                "1",
                "jar",
                null)).isInstanceOf(IOException.class);
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).doesNotExist();
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).doesNotExist();
    }
}
