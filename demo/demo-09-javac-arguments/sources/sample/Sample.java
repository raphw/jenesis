package sample;

import java.lang.reflect.Parameter;

public class Sample {

    public String greet(String recipient) {
        return "Hello, " + recipient + "!";
    }

    public static void main(String[] args) throws NoSuchMethodException {
        Parameter parameter = Sample.class.getDeclaredMethod("greet", String.class).getParameters()[0];
        if (!parameter.isNamePresent()) {
            throw new IllegalStateException("Method parameter names were not compiled in - javac was not given -parameters");
        }
        System.out.println("Compiled with -parameters: greet's parameter is named '" + parameter.getName() + "'");
    }
}
