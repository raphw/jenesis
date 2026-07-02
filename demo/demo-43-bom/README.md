Bill of materials demo
======================

A curated set of versions and checksums, shared as one properties file instead
of repeated `@jenesis.pin` tags. The module declares only *what* it requires;
the BOM says *which version* and *which bytes*:

    /**
     * @jenesis.bom bom-platform.properties
     */
    module demo.bom {
        requires org.slf4j;
        exports sample;
    }

Local BOMs are files named `bom-<name>.properties` in the project's BOM
location - by default the configuration location, i.e. the project root. A
dash can never occur in a Java module name, so the file reference is
structurally distinct from a module coordinate. Here `bom-platform.properties`
carries the pins:

    org.slf4j = 2.0.16
    org.slf4j/slf4j-api = 2.0.16 SHA-256/a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a

The location is fixed per project (`-Djenesis.project.boms=<paths>` or the
`boms(...)` builder method to relocate; multiple locations are searched in
order, first hit wins) and deliberately not profile-resolved, so every build
sees the same BOMs.

Build it
--------

From this directory:

    java build/jenesis/Project.java

The BOM's checksums count as pins, so the build also passes strict pinning
without a single `@jenesis.pin` tag:

    java -Djenesis.dependency.pin=strict build/jenesis/Project.java

The BOM file format
-------------------

Keys use the pin token grammar minus the group - the group never appears
inside a BOM:

    acme.core = 2.1.0 SHA-256/...              0 slashes: the module acme.core
    org.slf4j/slf4j-api = 2.0.16 SHA-256/...   1 slash: Maven groupId/artifactId
    maven/com.acme/lib/jar = 1.0.0             2+ slashes: explicit repository/coordinate

Values follow the pin value grammar, `<version>[ <algo>/<hash>]`, including
leading-colon classifier qualifiers; checksums are optional. Platform guards
are not allowed inside a BOM file - guard the declaration instead.

Declaring a BOM
---------------

The `@jenesis.bom` tag mirrors `@jenesis.pin`:

    @jenesis.bom acme.platform                          floating latest from the module repository
    @jenesis.bom acme.platform 2.1.0 SHA-256/ab12...    fetched at a version, content-verified
    @jenesis.bom kotlinc/module/acme.platform 2.1.0     entries merge into the kotlinc group
    @jenesis.bom acme.platform 1.9.0 [legacy]           platform-guarded, like a guarded pin
    @jenesis.bom bom-platform.properties                a local file (this demo)
    @jenesis.bom kotlinc/bom-platform.properties        the same file, entries in the kotlinc group

A repository BOM is a properties file published at the module repository's
standard versioned path, `<module>/<version>/<module>.properties`; without a
version the unversioned `<module>/<module>.properties` latest is fetched,
which is only as reproducible as any unpinned dependency and cannot carry a
hash. The local `~/.jenesis` repository is consulted first, so an exported
BOM resolves before the remote one.

Precedence
----------

Local pins always win: an explicit `@jenesis.pin` overrides any BOM entry.
Between BOMs, the first declared wins a conflicting coordinate. Under
`-Djenesis.dependency.pin=strict` the BOM reference itself needs a checksum
when fetched from a repository (a local file is covered by the build's own
content tracking), and BOM-provided hashes satisfy the strict closure check,
as this demo shows.

Repinning
---------

The `pin` goal is BOM-aware. By default (`-Djenesis.pin.bom=keep`) it writes
no `@jenesis.pin` for a dependency the BOM already supplies - running
`java build/jenesis/Project.java pin` on this demo leaves `module-info.java`
untouched - and removes a pin line that became redundant when a BOM took over
its coordinate; versioned repository `@jenesis.bom` references get their
content hash written onto the declaration, so the BOM itself is pinned.
The inverse migration is

    java -Djenesis.pin.bom=flatten build/jenesis/Project.java pin

which removes the `@jenesis.bom` declarations and pins the resolved closure
in full, turning this demo's module-info into its `@jenesis.pin` equivalent.
Orthogonally, `-Djenesis.pin.checksum=false` writes versions without hashes,
for pins and BOM references alike.
