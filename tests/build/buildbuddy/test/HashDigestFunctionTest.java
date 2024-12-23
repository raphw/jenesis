package build.buildbuddy.test;

import build.buildbuddy.HashDigestFunction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

public class HashDigestFunctionTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void can_compute_hash() throws IOException, NoSuchAlgorithmException {
        Path file = Files.writeString(temporaryFolder.newFile("file").toPath(), "bar");
        byte[] hash = new HashDigestFunction("MD5").hash(file);
        assertThat(hash).isEqualTo(MessageDigest.getInstance("MD5").digest("bar".getBytes(StandardCharsets.UTF_8)));
    }
}
