package sample.app;

import org.apache.commons.lang3.StringUtils;
import sample.greeter.Greeter;

public class App {

    public String greet() {
        return StringUtils.capitalize(new Greeter().prefix());
    }
}
