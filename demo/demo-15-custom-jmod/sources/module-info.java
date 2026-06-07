/**
 * A modular sample whose build is customized to package extra, non-class content
 * into a `.jmod`, link it into a runtime image with `jlink`, and wrap that into a
 * self-contained application image with `jpackage`.
 *
 * The module itself is ordinary; everything interesting is in `build/Demo.java`,
 * which wraps the stock assembler to add a config file to the module's `.jmod`.
 * Its `main` reads that config back from the runtime's `conf/` at run time, and
 * logs through its one module dependency, `org.slf4j`. That dependency shows the
 * other half of the picture: `jlink` links it into the runtime as a plain jar,
 * alongside this module's content-bearing `.jmod`, and `jpackage` carries the
 * whole linked runtime into the image - so the launched app finds both.
 *
 * @jenesis.main sample.Sample
 * @jenesis.release 25
 * @jenesis.pin compile/module/org.slf4j 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
 */
module demo.config {
    requires org.slf4j;

    exports sample;
}
