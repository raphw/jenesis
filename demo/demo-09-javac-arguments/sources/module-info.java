/**
 * A modular Java sample that must be compiled with an extra {@code javac} flag.
 * Keeping method parameter names in the bytecode requires {@code javac
 * -parameters}, which Jenesis does not pass by default. A
 * {@code process-javac.properties} file in the configuration location supplies
 * it, and the {@code @jenesis.main} entry point reflects on its own method to
 * prove the names survived compilation.
 *
 * @jenesis.release 25
 * @jenesis.main sample.Sample
 */
module demo.javac.arguments {
    exports sample;
}
