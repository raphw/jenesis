Platform guard demo
===================

Selects a dependency **variant per platform**. Where `../demo-22-module-classifier`
commits one classified variant explicitly, this demo declares several pin lines for
the same module and lets the build pick one: each line may end with a bracketed
**guard**, and the line whose guard matches the active platform wins.

Build and run it
----------------

From this directory:

    java build/jenesis/Execute.java

which resolves the unguarded fallback (the modern `mutiny.zero` 1.1.1) and prints:

    Selected variant: the modern mutiny.zero 1.1.1

Then select the guarded variant by overriding the platform tokens:

    java -Djenesis.dependency.platform=legacy build/jenesis/Execute.java

which resolves the classified `jdk-flow` variant of 0.4.3 instead and prints:

    Selected variant: the legacy jdk-flow variant of mutiny.zero 0.4.3

No `target/` cleanup is needed between the two: the platform token set is a field
of the manifests step, so changing it invalidates exactly the selection and the
resolution that depends on it.

The guarded pins
----------------

    /**
     * @jenesis.pin mutiny.zero 1.1.1 SHA-256/2ba03737...
     * @jenesis.pin mutiny.zero :jdk-flow:0.4.3 SHA-256/0556f076... [legacy]
     */
    module demo.platform {
        requires mutiny.zero;
    }

The active platform is the token set of the `jenesis.dependency.platform` property
(comma-separated, case-normalized), defaulting to the detected operating system and
chipset: one of `windows`/`linux`/`macos` plus one of `x86_64`/`aarch64`. A guard
matches when all of its tokens are contained in the active set; the most specific
match (largest guard) wins, the unguarded line is the fallback, equally specific
distinct matches fail the build, and an unmatched guard without a fallback leaves
the module unpinned (which strict pinning rejects).

A real OS-dependent project guards with platform tokens, one line per variant:

    @jenesis.pin org.openjfx.javafx.base :win:21.0.3 SHA-256/... [windows]
    @jenesis.pin org.openjfx.javafx.base :mac-aarch64:21.0.3 SHA-256/... [macos,aarch64]
    @jenesis.pin org.openjfx.javafx.base :linux:21.0.3 SHA-256/...

This demo deliberately guards with the neutral token `legacy` instead, so the
differential selection is observable and CI-testable on every machine: both
variants are pure Java, expose the same `java.util.concurrent.Flow` API, and
require nothing but `java.base`. The entry point tells them apart by the declared
module version (1.1.1 declares one, the `jdk-flow` jar does not). Because tokens
are free-form, the same mechanism covers custom build flavors (`fips`, `musl`)
beyond the OS and chipset defaults.

Reproducibility
---------------

Every variant stays committed in source with its own checksum, so the build is
reproducible from the repository alone on any platform: selection only decides
*which* committed, checksum-validated line applies. The `pin` goal applies the
same guard matching: it refreshes only the line that matched the local platform,
keeping its guard, and preserves every non-matching line byte-for-byte, since a
repin on one machine only observes the variant that machine selected. Keys
without guards are rewritten as usual.
