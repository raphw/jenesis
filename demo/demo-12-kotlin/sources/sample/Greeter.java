package sample;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

public class Greeter {

    public String prefix() {
        Properties messages = new Properties();
        try (InputStream input = Greeter.class.getResourceAsStream("/messages.properties")) {
            if (input == null) {
                throw new IllegalStateException("messages.properties was not packaged onto the module path");
            }
            messages.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return messages.getProperty("greeting");
    }
}
