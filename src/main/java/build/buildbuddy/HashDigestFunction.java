package build.buildbuddy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashDigestFunction implements HashFunction {

    private final String algorithm;

    public HashDigestFunction(String algorithm) {
        this.algorithm = algorithm;
    }

    @Override
    public byte[] hash(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (FileChannel channel = FileChannel.open(file)) {
            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
        }
        return digest.digest();
    }
}
