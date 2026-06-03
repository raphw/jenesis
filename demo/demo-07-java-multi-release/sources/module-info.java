/**
 * A modular multi-release sample. Its sole build descriptor is this
 * {@code module-info.java} (no {@code pom.xml}), so Jenesis auto-detects the
 * MODULAR_TO_MAVEN layout and emits a modular jar alongside a generated POM.
 *
 * The main compile targets Java 21 via {@code @jenesis.release 21}, and the
 * utility {@code sample.Platform} is overridden for Java 25 under
 * {@code sources/META-INF/versions/25/}. The runtime loads whichever copy
 * matches its own feature version, so the same jar prints a different line on
 * Java 21 than on Java 25.
 *
 * @jenesis.release 21
 * @jenesis.main sample.Sample
 */
module demo.multirelease {
    exports sample;
}
