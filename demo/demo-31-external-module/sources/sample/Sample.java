package sample;

public class Sample {

    // The ${greeting} placeholder below is rewritten before the regular Java
    // compilation runs - not by an inline build step, but by a plugin that
    // Jenesis loads with InternalModule and that uses the org.json dependency
    // to drive the substitution. Built without the plugin the literal survives.
    public static final String GREETING = "${greeting}";

    public static void main(String[] args) {
        System.out.println(GREETING);
    }
}
