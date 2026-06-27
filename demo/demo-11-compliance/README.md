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

Both checks are off by default. Each is turned on by a single property, read as
the step's default, and is otherwise inert - so a project carries the compliance
module without paying for it until a policy is set.

Build it
--------

From this directory:

    java build/jenesis/Project.java

The project ships a `jenesis.properties` that sets
`jenesis.compliance.license=Apache`, so the license check runs. The one
dependency, `commons-lang3`, is Apache-2.0, so the build passes and a report is
written. Point the property at a license the dependency does not carry (for
example `-Djenesis.compliance.license=MIT`) and the same build fails.

License check
-------------

`jenesis.compliance.license` is a comma-separated allow list of license-name
fragments, matched case-insensitively against each dependency's declared license.
The build fails when a resolved dependency carries no license from the list; with
the property unset the check does not run.

    -Djenesis.compliance.license=Apache,MIT,BSD

The verdicts are written to `reports/compliance/licenses.txt`, one line per
dependency (`OK`, `DENIED`, or `UNKNOWN`).

Vulnerability check
-------------------

`jenesis.compliance.vulnerability` is a severity threshold
(`low`/`medium`/`high`/`critical`). When set, the build queries the public
[OSV.dev](https://osv.dev) database (no account, no API key) for the resolved
Maven coordinates and fails when a matched advisory is at or above the threshold:

    -Djenesis.compliance.vulnerability=high

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
    |-- jenesis.properties         jenesis.compliance.license=Apache
    `-- sources
        `-- compliance
            `-- Sample.java        uses commons-lang3
