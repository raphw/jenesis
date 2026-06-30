/**
 * A single modular Java project wired to the inferred code-quality tools.
 * There is no build script: dropping a tool's configuration file into the
 * project root opts that tool in. Checkstyle and PMD lint the sources,
 * SpotBugs analyses the compiled classes, and the selected Java formatter
 * verifies the source layout. Each tool resolves in its own group, pinned
 * independently of the module's own dependencies.
 *
 * @jenesis.release 25
 */
module demo.quality {
    exports sample;
}
