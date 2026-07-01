package vulnerabilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Sample {

    private static final Logger LOGGER = LogManager.getLogger(Sample.class);

    public void greet() {
        LOGGER.info("hello from a project whose dependencies are scanned for known vulnerabilities!");
    }
}
