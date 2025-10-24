package build.jenesis;

import module java.base;

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
