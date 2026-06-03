package sample;

import org.apache.commons.lang3.StringUtils;

public class Sample {

    public static void main(String[] args) {
        String who = args.length == 0 ? "world" : String.join(" ", args);
        System.out.println("Hello, " + StringUtils.capitalize(who)
                + ", from a packaged Maven project built by Jenesis!");
    }
}
