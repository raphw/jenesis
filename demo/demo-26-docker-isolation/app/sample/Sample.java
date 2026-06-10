package sample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Sample {

    public static void main(String[] args) throws IOException {
        peek("program main");
    }

    public static void peek(String actor) throws IOException {
        String token = System.getenv("DEMO_SECRET");
        System.out.println("[" + actor + "] env DEMO_SECRET = "
                + (token == null ? "<unset - out of reach>" : token));
        Path file = Path.of(System.getProperty("user.home"), ".demo-credentials");
        if (!Files.isRegularFile(file)) {
            System.out.println("[" + actor + "] cannot read " + file + " - out of reach");
            return;
        }
        System.out.println("[" + actor + "] extracted " + file + ": " + Files.readString(file).strip());
        Files.writeString(file, "overwritten by the " + actor + System.lineSeparator());
        System.out.println("[" + actor + "] overwrote " + file);
    }
}
