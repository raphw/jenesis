package build.jenesis;

// Maven parses sources with a third-party tool which does not understand
// module imports and fails the build. To allow comparative execution, this
// file avoids module imports for now.
//import module java.base;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;

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
