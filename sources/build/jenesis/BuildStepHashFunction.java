package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildStepHashFunction {

    byte[] hash(BuildStep step) throws IOException;

    static BuildStepHashFunction ofDigest(String algorithm) {
        return step -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(step);
            } catch (NotSerializableException e) {
                bytes.reset();
            }
            try {
                return MessageDigest.getInstance(algorithm).digest(bytes.toByteArray());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
