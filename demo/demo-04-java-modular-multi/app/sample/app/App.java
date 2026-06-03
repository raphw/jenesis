package sample.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.greeter.Greeter;

public class App {

    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    public String greet() {
        LOGGER.info("greeting requested");
        return new Greeter().prefix();
    }
}
