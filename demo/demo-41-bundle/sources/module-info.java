/**
 * A modular Java app with a {@code main} method and one real module dependency
 * ({@code org.slf4j}), shipped as a {@code bundle.zip}: the runtime launch closure
 * (the app jar plus {@code slf4j-api}) split into a {@code modulepath/}, with an
 * {@code application.properties} naming the entry point - a single, self-contained
 * input to drop onto a stock JRE base image. It is the bundle counterpart of the
 * jpackage app-image in {@code ../demo-06-java-modular-executable}.
 *
 * The {@code @jenesis.main} tag names the module's main class, which is what makes
 * the module runnable: the build records {@code main=sample.Sample} in the module's
 * {@code module.properties}, and the {@code bundle} step reads it (from the derived
 * {@code launcher.properties}) to write the bundle's {@code application.properties}.
 *
 * @jenesis.release 25
 * @jenesis.main sample.Sample
 * @jenesis.pin org.slf4j 2.0.16
 * @jenesis.pin org.slf4j/slf4j-api 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 */
module demo.bundle {
    requires org.slf4j;

    exports sample;
}
