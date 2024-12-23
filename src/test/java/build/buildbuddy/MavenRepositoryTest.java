package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path repository;

    private MavenRepository mavenRepository;

    @Before
    public void setUp() throws Exception {
        repository = temporaryFolder.newFolder("repository").toPath();
        mavenRepository = new MavenRepository(repository.toUri()); // comment out argument to test against Maven Central.
    }

    @Test
    public void can_download_dependency() throws IOException  {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("junit/junit/4.13.2"))
                .resolve("junit-4.13.2.jar"))) {
            writer.write("foo");
        }
        Path dependency = temporaryFolder.newFile("dependency").toPath();
        try (
                InputStream inputStream = mavenRepository.download("junit", "junit", "4.13.2", null, "jar");
                OutputStream outputStream = Files.newOutputStream(dependency)
        ) {
            inputStream.transferTo(outputStream);
        }
        assertThat(dependency).content().isEqualTo("foo");
    }
}