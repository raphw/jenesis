package sample;

public class Sample {

    public static void main(String[] args) {
        System.out.println("Running on Java " + Runtime.version().feature() + ".");
        System.out.println(Platform.greeting());
    }
}
