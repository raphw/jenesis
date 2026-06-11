package sample.greeter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Greeter {

    private static final Logger LOGGER = LoggerFactory.getLogger(Greeter.class);

    public String prefix() {
        LOGGER.info("building greeting");
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
