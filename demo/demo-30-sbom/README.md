Software bill of materials demo
===============================

Emit a CycloneDX software bill of materials (SBOM) for a project. A small Maven
project depends on one library; turning the SBOM on records every resolved
component, its version, content hash, and declared license into a CycloneDX
document. There is no build script and no SBOM plugin to configure: a single
property selects the format.

This is a supply-chain counterpart to the dependency-graph view (the
`dependencies` selector): where that prints the graph, this freezes it into a
publishable, machine-readable artifact.

Build it
--------

From this directory:

    java build/jenesis/Project.java

The project ships a `jenesis.properties` that sets `jenesis.sbom.cyclonedx=json`,
so a plain build emits the SBOM. Pass `xml` for the XML form instead; any other
value fails the build.

Layout
------

    demo/demo-30-sbom
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    pins commons-lang3 (Apache-2.0)
    |-- jenesis.properties         jenesis.sbom.cyclonedx=json
    `-- sources
        `-- sbom
            `-- Sample.java        uses commons-lang3

How the SBOM is produced
------------------------

The default Java assembler runs an `Sbom` step before the jar is sealed. It
reads the module's resolved dependency graph, its content hashes, and the
licenses captured during resolution, and emits a CycloneDX document in one move
(no external tool). The SBOM is placed three ways, each for a different consumer:

- **Embedded in the jar**, at `META-INF/sbom/<artifact>.cdx.json`, so the bill of
  materials travels inside the artifact. The jar's `MANIFEST.MF` records
  `Sbom-Format: CycloneDX` and `Sbom-Location` so a consumer can find it.
- **As a report**, collected on `stage` into `target/stage/reports/sbom/<module>/`
  alongside the other build reports, and recorded in each module's
  `inventory.properties` as a `<module>.report.sbom` entry.
- **As a Maven attachment**, when a Maven repository is staged (the `MAVEN` and
  `MODULAR_TO_MAVEN` layouts): `stage` drops `<artifact>-<version>-cyclonedx.json`
  next to the pom and jar, so `export` publishes it to Maven Central as the
  conventional CycloneDX attached artifact.

Where it lands
--------------

    target/build/.../assemble/binary/artifacts/jar/output/artifacts/classes.jar   (embeds META-INF/sbom/)
    target/stage/reports/sbom/<module>/sbom-demo-1.0.0.cdx.json                    (the report)
    target/stage/maven/output/.../sbom-demo/1.0.0/sbom-demo-1.0.0-cyclonedx.json   (the attachment)

The document is CycloneDX 1.6. Because the project depends on `commons-lang3`,
the SBOM lists it as a component carrying its `pkg:maven/...` package URL, its
`SHA-256` hash, and its `Apache-2.0` license, with a `dependsOn` relationship
back to the project.

Pinning
-------

The single dependency is pinned in the POM the usual way, so the component hash
recorded in the SBOM is reproducible. Nothing extra resolves for the SBOM itself:
it is emitted in-process from state the build already has.
