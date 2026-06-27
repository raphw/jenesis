Dependency compliance demo
==========================

Gate a build on the licenses and known vulnerabilities of its resolved
dependencies. A small Maven project depends on one library; turning compliance on
checks every resolved component against a policy and fails the build when the
policy is violated. There is no build script and no scanner plugin to configure:
a property selects the policy.

This is the supply-chain counterpart to the [`sbom`](../demo-10-sbom/README.md)
demo: where that freezes the resolved graph into a bill of materials, this
enforces a policy over the same graph.

The license check runs by default and fails on a dependency with no recognized
license; the vulnerability check is off until its property is set. Both are
configured by properties read as the steps' defaults.

Build it
--------

From this directory:

    java build/jenesis/Project.java

The license check runs over the project's `main` compile/runtime dependencies. The
one dependency, `commons-lang3`, declares Apache-2.0, so it passes and a report is
written. The shipped `jenesis.properties` also sets `jenesis.license.allowed=Apache`
to demonstrate an allow list; point it at a license the dependency does not carry
(`-Djenesis.license.allowed=MIT`) and the build fails.

License check
-------------

The check runs by default over the shipped (`main` compile/runtime) dependencies;
in-build snapshots and build-tool closures are excluded. Each dependency's declared
license (name and URL, with parent-POM inheritance, falling back to the jar's
`Bundle-License` manifest header) is normalized to a canonical SPDX id and category.

- **Missing licenses** are gated by `jenesis.license.unknown` =
  `ignore` | `warn` | `fail`, **default `fail`** - "no declared license" is legally
  all-rights-reserved, so the strict default refuses it. Set `ignore` to relax.
- **An allow list** (`jenesis.license.allowed`, optional) fails any dependency
  whose license is not on it. Entries match the SPDX id, the category, or the raw
  name/URL, so `Apache-2.0`, `Apache`, or `permissive` all match an Apache license.
  A dependency with several licenses passes if *any one* is allowed (Maven lists
  licenses disjunctively).

      -Djenesis.license.allowed=Apache-2.0,MIT,BSD-3-Clause
      -Djenesis.license.allowed=permissive,weak-copyleft

- **Overrides** curate a wrong or empty declaration. `jenesis.license.override`
  points at a properties file keyed by the internal dependency coordinate (the
  `maven/` repository prefix, with or without a version):

      # overrides.properties
      maven/org.example/widget=Apache-2.0
      maven/org.example/legacy/1.0.0=MIT

      -Djenesis.license.override=overrides.properties

Verdicts are written to `reports/compliance/licenses.txt`, one line per dependency
(`OK`, `DENIED`, `MISSING`, `WARN`, or `UNKNOWN`).

Vulnerability check
-------------------

`jenesis.vulnerability.severity` is a severity threshold
(`low`/`medium`/`high`/`critical`). When set, the build queries the public
[OSV.dev](https://osv.dev) database (no account, no API key) for the resolved
Maven coordinates and fails when a matched advisory is at or above the threshold:

    -Djenesis.vulnerability.severity=high

Vulnerability check
-------------------

`jenesis.vulnerability.severity` is a severity threshold
(`low`/`medium`/`high`/`critical`). When set, the build queries the public
[OSV.dev](https://osv.dev) database (no account, no API key) for the resolved
Maven coordinates and fails when a matched advisory is at or above the threshold:

    -Djenesis.vulnerability.severity=high

Findings are written to `reports/compliance/vulnerabilities.txt`. The OSV fetch
only runs when this property is set, so an unconfigured build never reaches the
network. This demo leaves it off, so the build stays offline and deterministic.

How it works
------------

The default Java assembler runs a `ComplianceModule` after the `Sbom` step, over
the same resolved dependency graph. Each concern is a separate step:

- `LicenseCheck` reads the licenses captured during resolution and evaluates the
  allow list.
- `OsvDownload` queries OSV.dev and writes an advisory feed; `VulnerabilityCheck`
  matches the resolved coordinates against it and applies the threshold.

Both fail the build by throwing, the same way strict dependency pinning does, and
both are skipped entirely when their property is unset.

Layout
------

    demo/demo-11-compliance
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    pins commons-lang3 (Apache-2.0)
    |-- jenesis.properties         jenesis.license.allowed=Apache
    `-- sources
        `-- compliance
            `-- Sample.java        uses commons-lang3
