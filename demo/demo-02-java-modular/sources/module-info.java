/**
 * A purely modular Java sample. Its sole build descriptor is this
 * {@code module-info.java}; there is no {@code pom.xml}, so Jenesis
 * auto-detects the MODULAR_TO_MAVEN layout, resolves the declared module
 * dependency through the Jenesis module repository, and emits a modular jar
 * alongside a generated POM.
 *
 * @jenesis.release 25
 * @jenesis.pin compile/module/org.slf4j 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 */
module demo.modular {
    requires org.slf4j;

    exports sample;
}
