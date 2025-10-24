package build.jenesis.test;

import build.jenesis.HashDigestFunction;

import module java.base;
import module org.junit.jupiter.api;

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
