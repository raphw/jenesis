/**
 * The consumer module. It requires the sibling demo.greeter module built within
 * this same project, which re-exports the external org.slf4j module via
 * {@code requires transitive}. This module therefore reads slf4j transitively
 * without declaring it directly, yet still pins it as part of its own closure.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 */
module demo.app {
    requires demo.greeter;
}
