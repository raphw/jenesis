Build profiles demo
===================

Configure a build with *profiles*: property files that switch features on as a
named set, instead of repeating long `-D` flag lists. A profile is just a
`*.properties` file at the project root; selecting one turns on whatever
properties it declares. This demo ships a `release` profile that, in one switch,
attaches source jars and a software bill of materials.

There is no profile registry and no plugin: a profile is selected by naming it in
`jenesis.project.properties`, and profiles compose by chaining to each other.

Build it
--------

A plain build is the development build - no extras:

    java build/jenesis/Project.java

Select the `release` profile to build for publication:

    java -Djenesis.project.properties=release build/jenesis/Project.java stage

The release build additionally stages a `-sources.jar` and a
`-cyclonedx.json` SBOM next to the jar, ready for `export`.

Layout
------

    demo/demo-13-profiles
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    pins commons-lang3
    |-- release.properties         the release profile (sources + chains to supply-chain)
    |-- supply-chain.properties    a profile that turns on the SBOM
    `-- sources
        `-- profiles
            `-- Sample.java

How profiles work
-----------------

Property files feed the same `jenesis.*` system properties the command line sets,
so anything you can pass with `-D` can live in a profile. They are loaded before
the build is configured, by the `main` launcher:

- `jenesis.properties` at the project root is the **base file**. It is always
  loaded when present, and it is optional - this demo ships none.
- `jenesis.project.properties` is a **comma-separated list of profiles** to load,
  resolved relative to the folder of the file that names them. The `.properties`
  suffix is optional, so `release` and `release.properties` are equivalent. A
  listed profile that does not exist is an error (only the base files are optional).
- Profiles **chain**: any loaded file may itself set `jenesis.project.properties`
  to pull in more, transitively, until everything is loaded. Here `release`
  chains to `supply-chain`:

      release.properties        jenesis.project.sources=true
                                jenesis.project.properties=supply-chain
      supply-chain.properties   jenesis.sbom.cyclonedx=json

  so selecting `release` also applies `supply-chain`.
- A user-global `jenesis.properties` is loaded for **every** project as the
  weakest layer - shared personal defaults. It lives in `~/.jenesis/`;
  `jenesis.project.global` moves that folder (default `$HOME`) or, set to an empty
  string, switches it off, and a missing file is ignored. It may declare its own
  profiles too, relative to its `.jenesis` folder.
- **Precedence**, highest first: an explicit `-D` on the command line, then the
  profiles, then the project `jenesis.properties`, then the user-global
  `jenesis.properties`. So `-Djenesis.project.sources=false` on a release build
  switches the source jar back off (the command line always wins), and selecting
  the `release` profile overrides whatever the project's base `jenesis.properties`
  set.

What the release build produces
-------------------------------

    target/stage/maven/output/.../profiles-demo/1.0.0/profiles-demo-1.0.0.jar
    target/stage/maven/output/.../profiles-demo/1.0.0/profiles-demo-1.0.0-sources.jar      (release only)
    target/stage/maven/output/.../profiles-demo/1.0.0/profiles-demo-1.0.0-cyclonedx.json   (release only)

The plain build produces only the first; the `release` profile adds the other two
without changing a single command-line flag beyond selecting the profile.
