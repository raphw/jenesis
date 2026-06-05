/**
 * The consumer module. It requires the sibling demo.greeter module built within
 * this same project and the external org.slf4j named module, so a single build
 * resolves an intra-project sibling and an external artifact side by side.
 *
 * @jenesis.release 25
 * @jenesis.pin compile/module/org.slf4j 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 */
module demo.app {
    requires demo.greeter;
    requires org.slf4j;
}
