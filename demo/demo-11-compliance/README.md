Dependency licensing demo
=========================

Gate a build on the licenses of its resolved dependencies. A small Maven project
depends on two libraries: a permissively licensed one (Apache `commons-lang3`) and a
copyleft one (GPL `mysql-connector-java`). The policy requires permissive licenses,
so the license check passes the first, **rejects** the second, and fails the build.
There is no build script and no scanner plugin to configure: a property file selects
the policy.

This is the supply-chain counterpart to the [`sbom`](../demo-10-sbom/README.md)
demo: where that freezes the resolved graph into a bill of materials, this enforces
a policy over the same graph. Its sibling
[`vulnerabilities`](../demo-12-vulnerabilities/README.md) demo applies the other
compliance gate, scanning the same graph against the OSV.dev advisory database.

The check is off until its property file exists in the configuration directory (the
project root by default): the license check reads `licensing.properties`. The file's
presence enables the step; its contents configure it.

Run it
------

From this directory:

    java build/Demo.java

The build is *meant* to fail, so `Demo.java` runs it and asserts the failure, the
way the `supply-chain-security` demo does, printing:

    [blocked] a GPL dependency under a permissive-only license policy
    The license check blocked the build, as expected.

The shipped `licensing.properties` enables the license check (it stays off until
that file exists) and sets `allowed=permissive`, requiring a permissive license on
every resolved `main` compile/runtime dependency. The verdicts are written to
`reports/compliance/licenses.txt`, one line per dependency:

    mysql/mysql-connector-java/5.1.49 [DENIED] The GNU General Public License, Version 2
    org.apache.commons/commons-lang3/3.14.0 [OK] Apache-2.0

`commons-lang3` is Apache-2.0, a permissive license, so it passes. The JDBC driver
is GPL-2.0, a strong-copyleft license that is **not** permissive, so it is denied and
the build fails. Relax the policy to admit it (`allowed=permissive,strong-copyleft`,
or name the license outright, or an `override` for the coordinate) and the build
passes again.

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
  `Apache-2.0`, `Apache`, or `permissive` all match an Apache license, while
  `strong-copyleft` matches the GPL family. A dependency with several licenses passes
  if *any one* is allowed (Maven lists licenses disjunctively). A `denied` list (same
  syntax) rejects matches outright.

      allowed=Apache-2.0,MIT,BSD-3-Clause
      allowed=permissive,weak-copyleft

- **`override.<coordinate>`** curates a wrong or empty declaration, keyed by the
  internal dependency coordinate (the `maven/` repository prefix, with or without a
  version):

      override.maven/org.example/widget=Apache-2.0
      override.maven/org.example/legacy/1.0.0=MIT

Verdicts are written to `reports/compliance/licenses.txt`, one line per dependency
(`OK`, `DENIED`, `MISSING`, `WARN`, or `UNKNOWN`).

How it works
------------

The default Java assembler runs a `ComplianceModule` after the `Sbom` step, over the
same resolved dependency graph. The `LicenseCheck` step reads the licenses captured
during resolution and evaluates the allow list, failing the build by throwing the
same way strict dependency pinning does. It is skipped entirely when
`licensing.properties` is absent. The module's other step, `VulnerabilityCheck`, is
shown in the [`vulnerabilities`](../demo-12-vulnerabilities/README.md) demo.

Layout
------

    demo/demo-11-compliance
    |-- build/Demo.java            runs the build and asserts the license check fails
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    pins commons-lang3 (Apache) and mysql-connector-java 5.1.49 (GPL)
    |-- licensing.properties       allowed=permissive
    `-- sources
        `-- compliance
            `-- Sample.java        uses commons-lang3
