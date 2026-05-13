/**
 * @release 25
 * @tests build.jenesis
 * @requires org.junit.jupiter 5.11.3
 * @requires org.assertj.core 3.27.0
 */
open module build.jenesis.test {

    requires build.jenesis;
    requires java.compiler;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
