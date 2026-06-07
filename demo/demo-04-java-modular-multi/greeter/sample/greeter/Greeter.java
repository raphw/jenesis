package sample.greeter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Greeter {

    private static final Logger LOGGER = LoggerFactory.getLogger(Greeter.class);

    public String prefix() {
        LOGGER.info("building greeting");
        return "hello from a multi-module modular project, compiled by Jenesis!";
    }
}
