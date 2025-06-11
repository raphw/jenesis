package build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    static void main(String[] args) throws IOException {
        if (Files.exists(Path.of("pom.xml"))) {
            Maven.main(args);
        } else {
            Modular.main(args);
        }
    }
}
