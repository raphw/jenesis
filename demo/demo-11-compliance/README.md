Dependency compliance demo
==========================

Gate a build on the licenses and known vulnerabilities of its resolved
dependencies. A small Maven project depends on one library; turning compliance on
checks every resolved component against a policy and fails the build when the
policy is violated. There is no build script and no scanner plugin to configure: a
property file selects the policy.

This is the supply-chain counterpart to the [`sbom`](../demo-10-sbom/README.md)
demo: where that freezes the resolved graph into a bill of materials, this
enforces a policy over the same graph.

Each check is off until its property file exists in the configuration directory
(the project root by default): the license check reads `licensing.properties` and
the vulnerability check reads `vulnerability.properties`. The file's presence
enables the step; its contents configure it.

Build it
--------

From this directory:

    java build/jenesis/Project.java

The shipped `licensing.properties` enables the license check (it stays off until
that file exists) and sets `allowed=Apache` as the allow list. The check runs over
the project's `main` compile/runtime dependencies; the one dependency,
`commons-lang3`, declares Apache-2.0, so it passes and a report is written. Point
the allow list at a license the dependency does not carry (`allowed=MIT`) and the
build fails.

License check
-------------

The check is off until a `licensing.properties` file is present; once it is, the
check runs over the shipped (`main` compile/runtime) dependencies, while in-build
snapshots and build-tool closures are excluded. Each dependency's declared license
(name and URL, with parent-POM inheritance) is normalized to a canonical SPDX id
and category. A dependency that declares none is read from its jar instead: the
embedded CycloneDX SBOM (via `Sbom-Location`), then the OSGi `Bundle-License`
header, then a `META-INF/LICENSE` text file matched heuristically to an SPDX id.

The file's keys:

- **`unknown`** = `ignore` | `warn` | `fail` gates missing licenses, **default
  `fail`**: "no declared license" is legally all-rights-reserved, so the strict
  default refuses it. Set `ignore` to relax.
- **`allowed`** (optional, comma-separated) fails any dependency whose license is
  not on the list. Entries match the SPDX id, the category, or the raw name/URL, so
  `Apache-2.0`, `Apache`, or `permissive` all match an Apache license. A dependency
  with several licenses passes if *any one* is allowed (Maven lists licenses
  disjunctively). A `denied` list (same syntax) rejects matches outright.

      allowed=Apache-2.0,MIT,BSD-3-Clause
      allowed=permissive,weak-copyleft

- **`override.<coordinate>`** curates a wrong or empty declaration, keyed by the
  internal dependency coordinate (the `maven/` repository prefix, with or without a
  version):

      override.maven/org.example/widget=Apache-2.0
      override.maven/org.example/legacy/1.0.0=MIT

Verdicts are written to `reports/compliance/licenses.txt`, one line per dependency
(`OK`, `DENIED`, `MISSING`, `WARN`, or `UNKNOWN`).

Vulnerability check
-------------------

A `vulnerability.properties` file enables the vulnerability check. Its `severity`
key is a threshold (`low`/`medium`/`high`/`critical`); the build queries the public
[OSV.dev](https://osv.dev) database (no account, no API key) for the resolved Maven
coordinates and fails when a matched advisory is at or above it. An optional
`osv.endpoint` key overrides the OSV endpoint.

    # vulnerability.properties
    severity=high

Findings are written to `reports/compliance/vulnerabilities.txt`. The OSV fetch
only runs when this file is present, so an unconfigured build never reaches the
network. This demo ships no `vulnerability.properties`, so the build stays offline
and deterministic.

How it works
------------

The default Java assembler runs a `ComplianceModule` after the `Sbom` step, over
the same resolved dependency graph. Each concern is a separate step:

- `LicenseCheck` reads the licenses captured during resolution and evaluates the
  allow list.
- `OsvDownload` queries OSV.dev and writes an advisory feed; `VulnerabilityCheck`
  matches the resolved coordinates against it and applies the threshold.

Both fail the build by throwing, the same way strict dependency pinning does, and
both are skipped entirely when their property file is absent.

Layout
------

    demo/demo-11-compliance
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    pins commons-lang3 (Apache-2.0)
    |-- licensing.properties       allowed=Apache
    `-- sources
        `-- compliance
            `-- Sample.java        uses commons-lang3
