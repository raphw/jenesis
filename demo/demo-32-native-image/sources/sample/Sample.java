package sample;

public class Sample {

    public static void main(String[] args) throws Exception {
        String who = args.length == 0 ? "world" : args[0];
        // The greeter is named at run time, so native-image's closed-world
        // analysis cannot see it: the binary needs reachability metadata, which
        // the tracing agent records from the test run.
        String name = args.length > 1 ? args[1] : "Greeter";
        Class<?> greeter = Class.forName("sample." + name);
        Object greeting = greeter.getMethod("greet", String.class)
                .invoke(greeter.getConstructor().newInstance(), who);
        System.out.println(greeting);
    }
}
