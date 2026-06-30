/**
 * A modular Java sample that selects a classified variant of a module
 * dependency. The {@code @jenesis.pin} value carries a leading-colon
 * classifier qualifier, {@code :jdk-flow:0.4.3}, so the build resolves
 * {@code mutiny.zero} to the {@code jdk-flow} artifact variant instead of
 * the default jar and verifies the fetched bytes against the pinned
 * checksum. The pure MODULAR layout is selected through
 * {@code jenesis.properties}, since classified variants resolve by Java
 * module name through the Jenesis module repository.
 *
 * @jenesis.release 25
 * @jenesis.main sample.Sample
 * @jenesis.pin mutiny.zero :jdk-flow:0.4.3 SHA-256/0556f076191921250e5c9e21b9674d252bf2c4c515491e087fec93f383292b17
 */
module demo.classifier {
    requires mutiny.zero;

    exports sample;
}
