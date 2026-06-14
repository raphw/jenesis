/**
 * A standard modular project: its only build descriptor is this
 * {@code module-info.java}, so Jenesis auto-detects the MODULAR_TO_MAVEN layout.
 * The {@code @jenesis.main} tag makes the module runnable through {@code Execute}.
 *
 * @jenesis.main sample.Sample
 */
module demo.dockerisolation {
    exports sample;
}
