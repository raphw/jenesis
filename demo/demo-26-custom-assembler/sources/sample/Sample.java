package sample;

public class Sample {

    // The ${greeting} placeholder below is rewritten by the custom assembler's
    // preprocessing step before the regular Java compilation runs. Built with
    // the stock InferredMultiProjectAssembler the literal would survive verbatim.
    public static final String GREETING = "${greeting}";

    public static void main(String[] args) {
        System.out.println(GREETING);
    }
}
