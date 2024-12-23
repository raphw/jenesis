package codes.rafael.buildbuddy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ChecksumNopDiffTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ChecksumNopDiff nop = new ChecksumNopDiff();

    @Test
    public void can_diff_empty() throws IOException {
        Path sources = temporaryFolder.newFolder("sources").toPath();
        Path first = toFile(sources, "first", "foo"), second = toFile(sources, "second", "bar");
        Path checksums = temporaryFolder.newFile("checksums.MD5").toPath();
        Files.delete(checksums);
        Map<Path, ChecksumStatus> traversal = ChecksumNopDiff.traverse(sources);
        assertThat(traversal).containsOnlyKeys(sources.relativize(first), sources.relativize(second));
        assertThat(traversal.get(sources.relativize(first))).isEqualTo(ChecksumStatus.ADDED);
        assertThat(traversal.get(sources.relativize(second))).isEqualTo(ChecksumStatus.ADDED);
    }

    private Path toFile(Path folder, String name, String content) throws IOException {
        Path file = folder.resolve(name);
        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.append(content);
        }
        return file;
    }

}