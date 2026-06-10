package profiles;

import org.apache.commons.lang3.StringUtils;

public class Sample {

    public String greet() {
        return StringUtils.capitalize("hello from a build configured by profiles!");
    }
}
