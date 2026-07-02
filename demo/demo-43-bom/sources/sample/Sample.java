package sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sample {

    private static final Logger LOGGER = LoggerFactory.getLogger(Sample.class);

    public String greet() {
        LOGGER.info("greeting requested");
        return "Hello from a BOM-pinned modular project, compiled by Jenesis!";
    }
}
