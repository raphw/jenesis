Supply-chain security demo
==========================

Guarantee that a build downloads exactly the dependency bytes you vetted, and
refuse to build when a dependency cannot be verified. This demo proves both
guarantees by deliberately getting them wrong in two modules and asserting that
each one fails the build: `unpinned` declares a dependency with a version but no
checksum, and `tampered` pins a dependency to a checksum that does not match the
real artifact.

Run it
------

    java build/Demo.java

`Demo.java` builds one module at a time with a `+<module>` selector and checks the
outcome of each:

    [ok]      unpinned: a version-only dependency builds by default
    [blocked] unpinned: a version-only dependency under strict pinning
    [blocked] tampered: a dependency whose pinned checksum does not match
    Both supply-chain checks blocked the build, as expected.

The first line shows the baseline: a version-only pin is accepted by default.
Passing `pinning(Pinning.STRICT)` (the in-code form of
`-Djenesis.dependency.pin=strict`) then turns any unpinned coordinate into a
build failure - useful in a hardened environment that should never download an
unverified artifact. The `tampered` module needs no such flag: `Dependencies` hashes
every fetched file and compares it to the pin, so a coordinate whose bytes do not
match its recorded `SHA-256` is rejected outright - exactly what would happen if a
repository served a swapped or compromised artifact.

Together these are the two halves of pinning: **strict pinning** decides *whether*
an unverified dependency may be used at all, and **checksums** verify that a
pinned dependency is the exact artifact you vetted. (See `../demo-01-java-pom` for
the everyday case: a dependency correctly pinned with its real checksum.)

The two modules in detail
-------------------------

Jenesis pins dependencies not just by version but by the **content checksum** of
every downloaded artifact, and can be told to **require** such a pin. The two
modules get one guarantee wrong each:

- **`unpinned`** declares a dependency with a version but **no checksum**. It
  builds by default, but fails under **strict pinning** - there is nothing to
  verify the download against.
- **`tampered`** pins the same dependency to a **wrong `SHA-256`**. It fails the
  build **even without** strict pinning: every download is checked against its
  pin regardless.

Layout
------

    demo-32-supply-chain-security
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- build/Demo.java          asserts both modules fail to build
    |-- pom.xml                  aggregator over the two modules
    |-- unpinned/pom.xml         commons-lang3 with a version but no checksum
    `-- tampered/pom.xml         commons-lang3 pinned to a deliberately wrong SHA-256

Both pins are wrong on purpose, so unlike the other demos this one is *not* a
project that builds - it is a project that must *not* build.

Updating pins: refresh versions and hashes
------------------------------------------

Pins freeze both the version and the checksum of every dependency, which is what
makes a build reproducible and resistant to a swapped artifact - but it also means
a pinned project never picks up a newer version on its own. To deliberately refresh
the pins, run `pin` with `-Djenesis.dependency.pin=ignore`:

    java -Djenesis.dependency.pin=ignore build/jenesis/Project.java pin

`ignore` drops every existing Jenesis pin: versions float to the latest the
repository offers, and the recorded checksums are not consulted. `pin` then
re-resolves that fresh closure and rewrites each `pom.xml` (or `module-info.java`)
with the new versions and freshly computed `SHA-256` checksums - leaving a normal
pinned project again, now tracking the latest reviewed versions.

Run against this demo, that same step would **heal** the `tampered` module:
`ignore` skips its deliberately-wrong all-zeros `SHA-256`, `pin` rehashes the real
artifact, and the wrong pin is replaced with the correct one - so the module that
fails today would build. (The demo ships it un-run, so the supply-chain check above
still has something to catch.)

That healing power is exactly why the operation is dangerous in the wrong hands: it
re-blesses whatever the repository currently serves, so a *swapped* artifact would
be written in as an accepted pin just the same. So because `ignore` bypasses
checksum verification while it resolves - it is the step that *establishes* trust
rather than enforcing it - run it only on a **trusted machine** against a **trusted
repository**. Review the resulting diff, commit it, and every subsequent build (the
default, or `-Djenesis.dependency.pin=strict`) enforces the new pins against the
artifacts you just vetted.

POMs are not pinned, and why strict pinning matters
---------------------------------------------------

Only the resolved **artifacts** (the jars) carry a checksum; the `pom.xml` files
read during resolution are not pinned. Some servers serve POMs as text and may
apply minor transformations - line-ending or whitespace normalization - that
would change a byte checksum without changing the dependency, so pinning them
would produce spurious mismatches.

That leaves one gap: a tampered POM could try to introduce a dependency the jar
checksums do not cover. **Strict pinning closes it.** Any dependency a POM newly
adds - or changes - arrives as a coordinate with no pin, which strict pinning
rejects, so a manipulated POM cannot quietly pull in an unverified artifact. For
that reason, enabling strict pinning is recommended for builds in unsecured
environments (a local machine) and for releases (CI), where the resolved set
should never drift from the one you reviewed.
