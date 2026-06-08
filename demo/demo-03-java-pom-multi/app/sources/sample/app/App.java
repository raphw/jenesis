package sample.app;

import sample.greeter.Greeter;

public class App {

    public String greet() {
        return new Greeter().prefix();
    }
}
