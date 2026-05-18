package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildExecutorModule {

    String PREVIOUS = "../";

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
    }

    default Optional<String> resolve(String path) {
        return Optional.of(path);
    }

    void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException;
}
