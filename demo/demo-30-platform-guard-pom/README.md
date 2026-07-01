Platform guard demo (Maven)
===========================

The `pom.xml` counterpart of `../demo-29-platform-guard`: a pin line in the
`<!--jenesis.pin ... -->` comment block may end with a bracketed **guard**, and
the line whose guard matches the active platform tokens wins. The rules are the
same as for `@jenesis.pin` tags in a `module-info.java`.

Build and run it
----------------

From this directory:

    java build/jenesis/Execute.java

which resolves the unguarded fallback and prints:

    Selected Variant: The Modern Commons-lang3 3.14.0

Then select the guarded variant by adding the `legacy` platform token:

    java -Djenesis.platform.legacy=true build/jenesis/Execute.java

    Selected Variant: The Legacy Commons-lang3 3.12.0

No `target/` cleanup is needed between the two: the platform is part of the
manifests step's cache identity, so adding the flag invalidates exactly the
selection and the resolution that depends on it.

The guarded pins
----------------

    <dependencies>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-text</artifactId>
            <version>1.12.0</version>
        </dependency>
    </dependencies>
    <!--jenesis.pin
    main/maven/org.apache.commons/commons-lang3 3.14.0 SHA-256/7b96bf3e...
    main/maven/org.apache.commons/commons-lang3 3.12.0 SHA-256/d919d904... [legacy]
    -->

The project declares `commons-text` and the guarded pins govern its
`commons-lang3` **transitive**: versions.properties acts as a bill of materials
that applies at any depth of the closure, so the guard switches which
`commons-lang3` the resolution picks, each line validated by its own checksum.
The entry point tells the variants apart by a class that only exists since
3.13 (`FluentBitSet`). The demo guards with the neutral token `legacy`, added
with `-Djenesis.platform.legacy=true`, so the selection is observable on every
machine; a real project guards with platform tokens (`[windows]`,
`[macos,aarch64]`) that match the detected OS and chipset directly.

Two Maven-specific boundaries are worth knowing. A **directly declared**
version is identity-like in Maven and wins over the bill of materials, so a
guarded pin cannot override the version written into a `<dependency>`
declaration - guard transitives (as here), or leave selection to the pin by
not declaring the dependency directly. And a guard cannot force in a
**classifier**: dependency management matches classifiers as part of the
coordinate key and never adds one to a declaration, in Jenesis exactly as in
Maven itself. Per-platform classifier selection exists only for Java modules
(see `../demo-28-module-classifier` and `../demo-29-platform-guard`), where a
module name has exactly one artifact on the module path and the classifier is
therefore a value, not an identity.

Re-pinning
----------

`java build/jenesis/Project.java pin` applies the same guard matching: only the
line that matched the local platform is refreshed from the resolved closure,
keeping its guard, and every non-matching line is preserved byte-for-byte. A
key that carries a guard stays in the comment block and is never migrated into
`<dependencyManagement>`, so the guarded table survives repins on any machine.
