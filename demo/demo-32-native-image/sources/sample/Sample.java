package sample;

public class Sample {

    public static void main(String[] args) {
        String who = args.length == 0 ? "world" : String.join(" ", args);
        System.out.println("Hello, " + who + ", from a native binary built by Jenesis!");
    }
}
