/**
 * A modular Java sample with an entry point and one real module dependency. As
 * with {@code ../java-modular}, its only build descriptor is this
 * {@code module-info.java} (no {@code pom.xml}), so Jenesis auto-detects the
 * MODULAR_TO_MAVEN layout and resolves the declared module through the Jenesis
 * module repository.
 *
 * The {@code @jenesis.main} tag names the module's main class. It is what lets
 * the {@code package} step (and the {@code Execute} launcher) treat this module
 * as runnable: the build records {@code main=sample.Sample} in the module's
 * {@code module.properties}, and when packaging is requested jpackage turns the
 * produced jar plus its runtime dependencies (here {@code org.slf4j}) into a
 * self-contained application image.
 *
 * @jenesis.release 25
 * @jenesis.main sample.Sample
 * @jenesis.pin main/maven/org.slf4j/slf4j-api 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 * @jenesis.pin org.slf4j 2.0.16
 */
module demo.modular.executable {
    requires org.slf4j;

    exports sample;
}
