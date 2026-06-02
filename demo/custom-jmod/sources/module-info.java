/**
 * A modular sample whose build is customized to package extra, non-class content
 * into a `.jmod` and link it into a runtime image with `jlink`.
 *
 * The module itself is ordinary; everything interesting is in `build/Demo.java`,
 * which wraps the stock assembler to add a config file to the module's `.jmod`.
 *
 * @jenesis.release 25
 */
module demo.config {
    exports sample;
}
