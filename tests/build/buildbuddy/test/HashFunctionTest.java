package build.buildbuddy.test;

import build.buildbuddy.HashFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class HashFunctionTest {

    @TempDir
    private Path folder;

    @Test
    public void can_write_file_and_read_file() throws IOException {
        Path file = folder.resolve("foo");
        HashFunction.write(file, Map.of(Path.of("foo"), new byte[]{1, 2, 3}));
        Map<Path, byte[]> checksums = HashFunction.read(file);
        assertThat(checksums).containsOnlyKeys(Path.of("foo"));
        assertThat(checksums.get(Path.of("foo"))).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    public void can_extract_folder() throws IOException {
        Files.writeString(folder.resolve("foo"), "bar");
        Map<Path, byte[]> checksums = HashFunction.read(folder, _ -> new byte[]{1, 2, 3});
        assertThat(checksums).containsOnlyKeys(Path.of("foo"));
        assertThat(checksums.get(Path.of("foo"))).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    public void can_extract_nested_folder() throws IOException {
        Files.writeString(Files.createDirectory(folder.resolve("bar")).resolve("foo"), "bar");
        Map<Path, byte[]> checksums = HashFunction.read(folder, _ -> new byte[]{1, 2, 3});
        assertThat(checksums).containsOnlyKeys(Path.of("bar/foo"));
        assertThat(checksums.get(Path.of("bar/foo"))).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    public void can_extract_empty_folder() throws IOException {
        Map<Path, byte[]> checksums = HashFunction.read(folder, _ -> {
            throw new UnsupportedOperationException();
        });
        assertThat(checksums).isEmpty();
    }
}
