package build.buildbuddy.test;

import build.buildbuddy.HashDigestFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

public class HashDigestFunctionTest {

    @TempDir
    private Path files;

    @Test
    public void can_compute_hash() throws IOException, NoSuchAlgorithmException {
        Path file = Files.writeString(files.resolve("file"), "bar");
        byte[] hash = new HashDigestFunction("MD5").hash(file);
        assertThat(hash).isEqualTo(MessageDigest.getInstance("MD5").digest("bar".getBytes(StandardCharsets.UTF_8)));
    }
}
