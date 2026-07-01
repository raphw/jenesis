Software bill of materials demo
===============================

Emit a CycloneDX software bill of materials (SBOM) for a project. A small Maven
project depends on one library; every build records every resolved component, its
version, content hash, and declared license into a CycloneDX document. There is no
build script and no SBOM plugin to configure: the SBOM is emitted by default, and an
optional `sbom.properties` file selects the format (or turns it off).

This is a supply-chain counterpart to the dependency-graph view (the
`dependencies` selector): where that prints the graph, this freezes it into a
publishable, machine-readable artifact.

Build it
--------

From this directory:

    java build/jenesis/Project.java

A plain build emits the SBOM: it is on by default. The project ships an optional
`sbom.properties` that sets `format=json`, which is also the default when no file is
present. Set `format=xml` for the XML form instead, or `format=none` to turn the SBOM
off; any other value fails the build. To suppress the SBOM without a file, pass
`-Djenesis.sbom.cyclonedx=false` (a default-`true` boolean override).

Layout
------

    demo/demo-12-sbom
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    project metadata (name, license, developers, scm) + pins commons-lang3
    |-- sbom.properties            format=json (also xml, or none to disable)
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

The `metadata.component` (the project itself) is described from the POM: its
`description`, its `Apache-2.0` license, its developers (rendered as CycloneDX
`authors`), and its homepage and source repository (rendered as `website` and
`vcs` external references). Jenesis fills in only what the POM actually declares:
an unset version (the `1-SNAPSHOT` placeholder) is omitted rather than fabricated,
since a version is not required for a valid SBOM.

The document also carries a `serialNumber` (`urn:uuid:...`) derived
deterministically from the document's own content, so a reproducible build
reproduces the exact same SBOM, serial number included. No creation `timestamp`
is written, because that cannot be made deterministic.

Pinning
-------

The single dependency is pinned in the POM the usual way, so the component hash
recorded in the SBOM is reproducible. Nothing extra resolves for the SBOM itself:
it is emitted in-process from state the build already has.
