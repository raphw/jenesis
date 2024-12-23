package codes.rafael.buildbuddy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ChecksumMd5DiffTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ChecksumMd5Diff md5ChecksumDiff = new ChecksumMd5Diff();

    @Test
    public void can_diff_empty() throws IOException, NoSuchAlgorithmException {
        Path sources = temporaryFolder.newFolder("sources").toPath();
        Path first = toFile(sources, "first", "foo"), second = toFile(sources, "second", "bar");
        Path checksums = temporaryFolder.newFile("checksums.diff").toPath();
        Files.delete(checksums);
        Map<Path, ChecksumMd5Diff.State> diffs = new LinkedHashMap<>();
        md5ChecksumDiff.diff(checksums, sources, diffs::put);
        assertThat(diffs).containsOnlyKeys(sources.relativize(first), sources.relativize(second));
        assertThat(diffs.get(sources.relativize(first)).status()).isEqualTo(ChecksumStatus.ADDED);
        assertThat(diffs.get(sources.relativize(first)).checksum()).isEqualTo(toMd5("foo"));
        assertThat(diffs.get(sources.relativize(first)).previous()).isNull();
        assertThat(diffs.get(sources.relativize(second)).status()).isEqualTo(ChecksumStatus.ADDED);
        assertThat(diffs.get(sources.relativize(second)).checksum()).isEqualTo(toMd5("bar"));
        assertThat(diffs.get(sources.relativize(second)).previous()).isNull();
    }

    @Test
    public void can_diff_previous() throws IOException, NoSuchAlgorithmException {
        Path sources = temporaryFolder.newFolder("sources").toPath();
        Path first = toFile(sources, "first", "foo"),
                second = toFile(sources, "second", "bar"),
                third = toFile(sources, "third", "qux");
        Path checksums = temporaryFolder.newFile("checksums.diff").toPath();
        try (BufferedWriter writer = Files.newBufferedWriter(checksums)) {
            writer.append("first");
            writer.newLine();
            writer.append(toMd5String("foo"));
            writer.newLine();
            writer.append("second");
            writer.newLine();
            writer.append(toMd5String("altered"));
            writer.newLine();
            writer.append("forth");
            writer.newLine();
            writer.append(toMd5String("removed"));
            writer.newLine();
        }
        Map<Path, ChecksumMd5Diff.State> diffs = new LinkedHashMap<>();
        md5ChecksumDiff.diff(checksums, sources, diffs::put);
        assertThat(diffs).containsOnlyKeys(sources.relativize(first),
                sources.relativize(second),
                sources.relativize(third),
                Path.of("forth"));
        assertThat(diffs.get(sources.relativize(first)).status()).isEqualTo(ChecksumStatus.RETAINED);
        assertThat(diffs.get(sources.relativize(first)).checksum()).isEqualTo(toMd5("foo"));
        assertThat(diffs.get(sources.relativize(first)).previous()).isEqualTo(toMd5("foo"));
        assertThat(diffs.get(sources.relativize(second)).status()).isEqualTo(ChecksumStatus.ALTERED);
        assertThat(diffs.get(sources.relativize(second)).checksum()).isEqualTo(toMd5("bar"));
        assertThat(diffs.get(sources.relativize(second)).previous()).isEqualTo(toMd5("altered"));
        assertThat(diffs.get(sources.relativize(third)).status()).isEqualTo(ChecksumStatus.ADDED);
        assertThat(diffs.get(sources.relativize(third)).checksum()).isEqualTo(toMd5("qux"));
        assertThat(diffs.get(sources.relativize(third)).previous()).isNull();
        assertThat(diffs.get(Path.of("forth")).status()).isEqualTo(ChecksumStatus.REMOVED);
        assertThat(diffs.get(Path.of("forth")).checksum()).isNull();
        assertThat(diffs.get(Path.of("forth")).previous()).isEqualTo(toMd5("removed"));
    }

    private Path toFile(Path folder, String name, String content) throws IOException {
        Path file = folder.resolve(name);
        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.append(content);
        }
        return file;
    }

    private static String toMd5String(String content) throws NoSuchAlgorithmException {
        return Base64.getEncoder().encodeToString(toMd5(content));
    }

    private static byte[] toMd5(String content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(content.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }
}