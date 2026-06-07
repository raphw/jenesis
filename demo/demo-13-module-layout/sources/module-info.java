/**
 * A purely modular Java sample built with the explicit MODULAR layout. Its sole
 * descriptor is this {@code module-info.java}; {@code build/Demo.java} selects
 * {@code Project.Layout.MODULAR}, so the declared module dependency is resolved by
 * Java module name against the Jenesis module repository and the output is a plain
 * modular jar with no generated {@code pom.xml}.
 *
 * @jenesis.release 25
 * @jenesis.pin compile/module/org.slf4j 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 */
module demo.modulelayout {
    requires org.slf4j;

    exports sample;
}
