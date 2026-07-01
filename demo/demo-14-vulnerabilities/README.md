Dependency vulnerability demo
=============================

Gate a build on the known vulnerabilities of its resolved dependencies. A small
Maven project depends on a deliberately vulnerable library; turning the
vulnerability check on scans every resolved component against the OSV.dev advisory
database and fails when a vulnerability at or above a configured severity is found.
There is no build script and no scanner plugin to configure: a property file
selects the policy.

This is the sibling of the [`compliance`](../demo-13-compliance/README.md) demo:
where that gates the same resolved graph on dependency licenses, this gates it on
known vulnerabilities. Both run as steps of the same `InferredComplianceModule`, each turned
on by the presence of its property file.

Run it
------

From this directory:

    java build/Demo.java

The build is *meant* to fail, so `Demo.java` runs it and asserts the failure,
printing:

    [blocked] log4j-core 2.14.1's critical Log4Shell advisory at the configured severity threshold
    The vulnerability check blocked the build, as expected.

The project depends on `log4j-core` 2.14.1, the version behind **Log4Shell**. The
shipped `vulnerability.properties` enables the check and sets `severity=critical`,
so the build queries OSV.dev for the resolved coordinates, finds that `log4j-core`
2.14.1 carries the critical advisory `GHSA-jfh8-c2jp-5v3q` (and others), and **fails
the build**. The full set of matched advisories is written to
`reports/compliance/vulnerabilities.txt` first. Set `warn=true` to downgrade the
finding to a warning that passes, or raise `severity` above the findings to let the
build through.

The version is pinned and never changes, so the advisories the demo reports are
stable: this is a historical vulnerability used on purpose, not a live dependency to
upgrade.

Vulnerability check
-------------------

A `vulnerability.properties` file enables the check. The build queries the public
[OSV.dev](https://osv.dev) database (no account, no API key) for the resolved Maven
coordinates and writes every match to `reports/compliance/vulnerabilities.txt`. The
file's keys:

- **`severity`** = `low` | `medium` | `high` | `critical` is the threshold; a matched
  advisory at or above it is flagged.
- **`warn`** = `true` | `false` (default `false`): a flagged advisory **warns**
  (reported, build passes) when `true`, or **fails** the build when `false`.
- **`osv.endpoint`** (optional) overrides the OSV endpoint (default
  `https://api.osv.dev`).

The OSV fetch runs only when this file is present; remove it to keep the build
offline.

How it works
------------

The default Java assembler runs an `InferredComplianceModule` after the `Sbom` step, over the
same resolved dependency graph. Two steps do the work: `OsvDownload` queries OSV.dev
and writes an advisory feed, and `VulnerabilityCheck` matches the resolved
coordinates against it and applies the threshold, failing the build by throwing the
same way strict dependency pinning does. Both are skipped entirely when
`vulnerability.properties` is absent.

The `pom.xml` excludes `log4j-core`'s transitive dependencies and re-declares
`log4j-api`, so the scanned graph is just the two log4j artifacts: `log4j-core`
2.14.1 ships its test tooling (junit, mockito) as compile-scope dependencies in a
known-buggy POM, and pruning them keeps the report focused on the vulnerability the
demo is about.

Layout
------

    demo/demo-14-vulnerabilities
    |-- build/Demo.java            runs the build and asserts the vulnerability check fails
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    pins log4j-core 2.14.1 (vulnerable) and log4j-api
    |-- vulnerability.properties   severity=critical
    `-- sources
        `-- vulnerabilities
            `-- Sample.java        uses log4j
