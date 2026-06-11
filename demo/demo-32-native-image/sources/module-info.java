/**
 * A modular Java sample compiled ahead of time into a single standalone native
 * executable with GraalVM `native-image`. As with `../demo-02-java-modular`, its
 * only build descriptor is this `module-info.java` (no `pom.xml`), so Jenesis
 * auto-detects the MODULAR_TO_MAVEN layout. The module is self-contained (no
 * external dependencies) so the ahead-of-time image needs no reachability
 * metadata to build.
 *
 * The `@jenesis.main` tag names the module's entry point, the same field the
 * `package` step keys off in `../demo-06-java-modular-executable`. Here it becomes
 * the `--module` launcher passed to `native-image`, so the produced binary starts
 * straight at `sample.Sample`.
 *
 * @jenesis.release 25
 * @jenesis.main sample.Sample
 */
module demo.graal.image {
    exports sample;
}
