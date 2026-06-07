/**
 * The library module of a multi-module modular project. It requires the
 * external org.slf4j module and re-exports it with {@code requires transitive},
 * so the consumer module reads slf4j transitively without declaring it itself.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 */
module demo.greeter {
    requires transitive org.slf4j;
    exports sample.greeter;
}
