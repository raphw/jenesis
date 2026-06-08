package sample.greeter;

import org.apache.commons.lang3.StringUtils;

public class Greeter {

    public String prefix() {
        return StringUtils.capitalize("hello from a multi-module Maven project, compiled by Jenesis!");
    }
}
