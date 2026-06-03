package sample;

import module org.slf4j;

public class Sample {

    private static final Logger LOGGER = LoggerFactory.getLogger(Sample.class);

    public static void main(String[] args) {
        String who = args.length == 0 ? "world" : String.join(" ", args);
        LOGGER.info("greeting {}", who);
        System.out.println("Hello, " + who + ", from a packaged Java module built by Jenesis!");
    }
}
