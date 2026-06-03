package sample;

import module java.base;
import module org.slf4j;

public class Sample {

    private static final Logger LOGGER = LoggerFactory.getLogger(Sample.class);

    public static void main(String[] args) throws IOException {
        Path config = Path.of(System.getProperty("java.home"), "conf", "app.properties");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(config)) {
            properties.load(reader);
        }
        LOGGER.info("read bundled config from {}", config);
        System.out.println("The packaged app read its bundled config from " + config + ":");
        System.out.println(properties.getProperty("greeting"));
    }
}
