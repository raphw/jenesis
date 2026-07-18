# Jenesis Codebase Audit

**Date:** 2026-07-18
**Scope:** `sources/build/jenesis/` (≈28,700 lines, 148 files) and `tests/build/jenesis/test/`.
**Method:** Six parallel review passes — a dedicated path-traversal/security sweep, two correctness sweeps (resolution subsystem; steps & orchestration), a performance/memory sweep, an I/O-network-robustness-and-usability sweep, and a test-gap analysis — followed by manual verification of the highest-severity claims directly against the source. Findings below are the verified, deduplicated result. Line numbers are indicative and may drift as the code changes.

Each finding carries a stable ID (`SEC-`, `BUG-`, `PERF-`, `IO-`, `USE-`, `TEST-`) so it can be referenced in follow-up work.

---

## Executive summary

| Severity | Security | Correctness | Perf/Memory | I/O & Usability |
|----------|:-------:|:-----------:|:-----------:|:---------------:|
| High/Critical | 2 | 3 | 1 | 1 |
| Medium | 1 | 4 | 4 | 4 |
| Low | 1 | 6 | 5 | 5 |

**Headline items to fix first:**

1. **SEC-1 / SEC-2 — Path traversal in Maven staging & `.m2` export.** Artifact coordinates read from POM text are turned into filesystem paths with no containment guard, while the sibling `MavenDefaultRepository` *does* guard the same operation. Reachable when building an untrusted project.
2. **BUG-1 — Pinned checksum silently dropped.** In `MavenPomResolver`, a coordinate requested with a concrete inline version bypasses its managed/pinned checksum entirely, so the artifact is fetched with no integrity check.
3. **BUG-4 — Resource-only edits serve a stale artifact.** In single-compiler modules, editing only a co-located resource file does not trigger a rebuild, so the old resource stays in the jar.
4. **IO-1 — No network timeouts.** The central fetch path (`Repository.open`) sets neither connect nor read timeout; a half-open server hangs the build forever, and the timeout-retry arm is dead code as a result.
5. **PERF-1 — OOM risk in incremental test selection.** Every class file of the module and of every runtime dependency jar is loaded fully into a single in-memory map.

A large amount of the codebase was checked and found **sound** — see the appendix. Notably, the core resolve path is well-guarded (`MavenDependencyKey.validate`, the `.m2` escape check, both cache zip-slip guards, same-origin credential scoping, no shell in any `ProcessBuilder`, no untrusted deserialization), and the recently-added module-alias feature's synthetic-coordinate handling is correct.

---

## 1. Security

### SEC-1 — [High] Path traversal in `.m2` repository export
**File:** `maven/MavenRepositoryExport.java:73-91` (sink); coordinates parsed unvalidated at `MavenRepositoryExport.java:273-302` (`Coordinates.parse`)
**Category:** path-traversal

The export step reads `groupId`/`artifactId`/`version` straight from staged POM text (`Coordinates.parse` applies no validation) and builds
`target.resolve(groupId.replace('.','/')).resolve(artifactId).resolve(version)` where `target` is `~/.m2/repository` (or `$MAVEN_REPOSITORY_LOCAL`), then `Files.createDirectories(...)`, `Files.deleteIfExists(destination)`, and `BuildStep.linkOrCopy(destination, source)`.

**Failure scenario:** a module whose emitted POM declares `<version>../../../../../../home/user/.config/whatever</version>` (or an `artifactId` containing `../`) writes the built jar/pom/sources to an attacker-chosen directory anywhere on disk and can delete a pre-existing file at the computed destination.

**Verified:** `Coordinates.parse` reads `getTextContent()` for each of the three elements with no filtering; the sink has no `normalize()`/`startsWith(target)` containment check. The sibling `MavenDefaultRepository.fetch` (`MavenDefaultRepository.java:253`) *does* perform exactly this check (`cached.normalize().startsWith(local.normalize())`) — this is a "sibling missing the guard" gap.

**Reachability caveat:** the coordinates are the *building project's own* POMs, not a remote dependency's, so this bites when building an untrusted project (CI, `jpx`-style execution of someone else's build), not through a poisoned dependency.

**Fix:** validate each coordinate segment with a `requireSafeSegment`-style check (reject `/`, `\`, `..`, control chars) and assert `targetVersionDir.normalize().startsWith(target.normalize())` before any `createDirectories`/`linkOrCopy`.

### SEC-2 — [High] Path traversal in Maven module staging
**File:** `maven/MavenRepositoryStaging.java:146-150` (sink); coordinates parsed at `MavenRepositoryStaging.java:294-315`
**Category:** path-traversal

Same defect class as SEC-1: `stageModules` builds `target.resolve(groupId.replace('.','/')).resolve(artifactId).resolve(version)` (here `target = context.next()`) from unvalidated POM coordinates, `createDirectories`, then `link()`s jars/pom/sources into it. `link()` only checks `!Files.exists(target)`.

**Failure scenario:** a module POM with `../` in `version`/`artifactId` lands staged artifacts outside the step's output folder — e.g. overwriting another module's outputs, or writing into the project checkout that a later step trusts.

Rated High rather than Critical because the traversal is relative to `context.next()` rather than the global `.m2`, but it still crosses the intended output boundary.

**Fix:** validate coordinate segments and assert `baseDir.normalize().startsWith(target.normalize())`.

### SEC-3 — [Medium] Unvalidated TSV-derived coordinates in the raw-git repository
**File:** `module/JenesisRawGitRepository.java:170-179` (path built + fetched); TSV parsed at `182-221`
**Category:** ssrf / path-confusion

`fetch()` validates its *input* `module`/`classifier`/`version` with `requireSafeSegment`, but `resolve()` then reads `groupId`/`artifactId`/`version` from columns of a **remotely fetched** `.tsv` and concatenates them into a fetch path (`groupId.replace('.','/') + "/" + artifactId + "/" + version + ...`) with no validation, then `repository.resolve(path)`. A malicious TSV row containing `../` rewrites the fetch target on the repository host.

The sibling `JenesisModuleRepository.fetch` (`JenesisModuleRepository.java:152-157`) normalizes the resolved URI and asserts it does not escape `root`; this implementation has no equivalent post-resolution containment check. Impact is bounded to a same-host URL, hence Medium.

**Fix:** run the TSV-derived segments through `requireSafeSegment`, and/or apply the sibling's `base.relativize(resolved)` containment check.

### SEC-4 — [Low] No containment check in module staging output resolution
**File:** `module/ModularStaging.java:54-64`
**Category:** path-traversal (defense-in-depth)

`target = context.next().resolve(moduleName).resolve(version)` from `inventory.properties` values, with no containment check; the `pom` path is `resolve(pomRelative).normalize()` but likewise unchecked. Inventory files are build-internal outputs, so reachability requires an attacker-controlled module name/version — lower than SEC-1/2, but the same missing guard.

**Fix:** validate `moduleName`/`version` as safe single segments and assert the resolved paths stay under `context.next()`.

> **Also see** the `MavenRepositoryStaging.resolve`/`sbomReport` helpers (`MavenRepositoryStaging.java:211-224`): they `normalize()` but guard only with `isRegularFile`/`isDirectory`, not `startsWith(base)` containment — a `../` inventory value pointing at a real file elsewhere would be linked/copied. Same defense-in-depth class as SEC-4.

---

## 2. Correctness bugs

### BUG-1 — [High] Pinned/managed checksum silently dropped for a concrete inline version
**File:** `maven/MavenPomResolver.java:61-63`
**Category:** correctness (integrity-check bypass)

`managed` (the dependency-management entry, which may carry a checksum) is looked up, but the branch taken when the caller supplies a concrete inline version constructs the checksum-less `MavenDependencyValue(declared, COMPILE, null, exclusions, null)` and never consults `managed.checksum()`. Only the `else if (managed != null)` branch threads the checksum. The artifact is then materialized with checksum `""`, so `Resolver.materialize` skips `validate()`.

**Verified:** the code path drops the checksum as described. **Reachability caveat:** requires a caller to pass the same coordinate both with an inline concrete version in `coordinates` *and* with a checksum-bearing managed entry — confirm whether any real wiring (a `@jenesis.pin g/a/1.0 <hash>` combined with an inline-versioned request) produces this combination before rating the exploitability; the code defect itself is unambiguous.

**Fix:** in the concrete-inline branch, when `managed != null`, merge the managed checksum and assert the declared version equals the managed version (else error) rather than discarding it.

### BUG-2 — [Medium] Checksum bound to one version applied to a different resolved version
**File:** `maven/MavenPomResolver.java:390-399` (`selectChecksum`)
**Category:** correctness

`checksums` is keyed by the *declared* version, but `selectChecksum`'s `size()==1` fallback returns that lone checksum for whatever `currentVersion` was *resolved*. When a coordinate is reached via two paths — one declaring `X:1.0` with a checksum, another declaring `X:2.0` observed first (so `2.0` wins) — the `2.0` artifact is validated against the `1.0` checksum and fails with a spurious `Mismatched digest for .../2.0`.

**Fix:** in the `size()==1` fallback, reuse the checksum only when its key equals `currentVersion` or was a floating token (RELEASE/LATEST/range); otherwise return `null`.

### BUG-3 — [Medium] `MavenUriParser` mis-parses standard Maven classifier filenames
**File:** `maven/MavenUriParser.java:28-34`
**Category:** correctness

The classifier extraction assumes the filename layout `artifactId-classifier-version.type`, but standard Maven is `artifactId-version-classifier.type`. For `commons-1.2-sources.jar` (version `1.2`, classifier `sources`) it produces classifier `1.2-sou`, yielding the coordinate `commons/jar/1.2-sou/1.2` — garbage. **Verified** by hand-tracing the index arithmetic.

**Reachability:** `MavenUriParser` has **no production caller inside `sources/`** — it is a public API / config surface (wired via `ofUris` from properties files), and its only test (`MavenUriParserTest.can_resolve_module_with_classifier`) uses the non-standard filename `qux-baz-1.jar`, so the test passes while encoding the wrong convention. Real bug, but not currently exercised by any in-tree flow — hence Medium, not High.

**Fix:** parse as `artifactId-version[-classifier].type` — strip the leading `artifactId + "-" + version`, and the remaining `-<classifier>` before the extension is the classifier. Fix the test fixture to a standard Maven filename.

### BUG-4 — [High] Resource-only edits do not invalidate single-compiler modules (stale artifact)
**File:** `step/Javac.java:88-105` (`hasRelevantChange`); wiring at `project/InferredCompilerChainModule.java:157-177`; siblings `project/{Kotlin,Scala,Groovy}CompilerModule.java`
**Category:** cache-correctness

**Verified end-to-end.** When a module has exactly one compiler, `InferredCompilerChainModule` does **not** add the standalone `Resources` step (`if (hasResource && compilers != 1)`, line 176) and instead sets `includeResources(true)` on that compiler. `Javac.process` copies every non-`.java` file under `sources/` into `classes/` (line 168). But `Javac.hasRelevantChange` returns true for a change under `sources/` only when the leaf name ends in a *source extension* (`.java`/`.kt`/…) — a changed resource file (e.g. `messages.properties`) is a non-source leaf and is ignored, so the step is skipped and the previous (stale) resource is served in the jar.

The multi-compiler path is safe because the separate `Resources` step uses the default whole-argument `shouldRun`.

**Fix:** in `hasRelevantChange`, when `includeResources` is in effect, treat *any* changed non-source file under `SOURCES` (excluding `META-INF/versions` and `META-INF/build.jenesis`, matching what `process` actually copies) as relevant — or have the resource-copying compilers fall back to the default whole-folder `hasChanged`.

### BUG-5 — [Medium] `Platform.select` throws spurious "Ambiguous" depending on guard declaration order
**File:** `Platform.java:65-84`
**Category:** correctness

Ambiguity is judged greedily *during* iteration. For guards `[windows]→A`, `[x86_64]→B`, `[windows,x86_64]→C` with active platform `windows,x86_64`, the two size-1 guards trip the ambiguity throw before the unambiguous size-2 guard `[windows,x86_64]` is ever considered. Whether it throws depends on the insertion order of the parsed guard set, so the same guards can pass or fail based on declaration order.

**Fix:** two-pass — compute the maximum specificity among *matching* guards first, then throw only if ≥2 matching guards at that maximum specificity carry distinct values.

### BUG-6 — [Medium] `Inventory.shouldRun` omits `identity.properties`, an input it reads and republishes
**File:** `step/Inventory.java:30-49` (`shouldRun`) vs `111-114` / `181-184` (`apply`)
**Category:** cache-correctness

`apply` reads each dependency's `identity.properties` and writes its coordinates into the inventory as `…identity.N`, but `shouldRun`'s tracked-path list omits `Path.of(IDENTITY)`. A change confined to `identity.properties` (a coordinate set shifting without any other tracked descriptor changing) is invisible to change detection, so `Inventory` is skipped and keeps stale coordinate entries that feed staging/`Execute`. (`Assign.shouldRun` correctly tracks `IDENTITY`; `Inventory` does not.)

**Fix:** add `Path.of(IDENTITY)` to the `hasChanged(...)` list in `Inventory.shouldRun`.

### BUG-7 — [Low] Platform-guard `endsWith("]")` collides with inclusive version ranges
**File:** `module/ModuleInfoParser.java:95-105` and `119-129`; `maven/MavenPomResolver.java:926-936`
**Category:** correctness

A pin/BOM whose value is a Maven inclusive range, e.g. `@jenesis.pin g/a [1.0,2.0]`, ends in `]` and is misread as a platform guard: `Platform.of("1.0,2.0")` and `version` becomes empty, so the pin is silently dropped. Exclusive-upper ranges (`)`) are unaffected.

**Fix:** treat a trailing `[...]` as a guard only when its content has no `,` and does not look like a version range.

### BUG-8 / BUG-9 / BUG-10 — [Low] Resource leaks on resolution error paths
- **BUG-8** `maven/MavenPomResolver.java:684-707` (`assembleOrCached`): the POM `InputStream` is opened before the checksum-format / `MessageDigest.getInstance` validation; if that validation throws, the stream is never closed.
- **BUG-9** `maven/MavenModuleResolver.java:56-71,124-127` (`toRootPom`): root/managed POM streams are eagerly opened and accumulated into lists; an exception while building a later entry (e.g. `No POM found for …`) leaks the earlier streams.
- **BUG-10** `module/JenesisModuleRepository.java:164-170` and `module/JenesisRawGitRepository.java:179`: `fetch()` opens the network stream eagerly and returns a one-shot `() -> stream`, leaking the socket if the item is discarded (e.g. an existence probe) and returning an exhausted stream on a second `toInputStream()`. This diverges from `MavenDefaultRepository`'s lazy `LatentRepositoryItem`, which defers the open to consumption time. *(Found independently by both the resolution and I/O passes.)*

**Fix (BUG-10, also a robustness win):** return `Optional.of(() -> Repository.open(uri, token, retry))` so the connection is created — and re-creatable — at consumption time and never opened for a discarded probe. For BUG-8/9, wrap opens in try/close-on-throw or validate before opening.

---

## 3. Performance & memory

### PERF-1 — [High] Incremental test selection loads all class bytes into memory at once
**File:** `project/TestModule.java:683-716` (`selected`), consumed by `project/TestSelection.java:13-28`
**Category:** memory

**Verified.** With incremental selection enabled, every `.class` of the module output *and* of every runtime dependency jar is `readAllBytes()` into a single live `Map<String,byte[]>`, then handed to `TestSelection.of`. On a realistic app with low-thousands of dependency jars this is hundreds of MB to multiple GB resident simultaneously → OOM on large classpaths.

**Fix:** the bytes are needed only transiently per class (a digest + constant-pool references). Stream each class once, extract the small per-class digest and reference set immediately, and never retain raw class bytes beyond one file.

### PERF-2 — [Medium] Each artifact is read and hashed twice per resolution
**File:** `maven/MavenDefaultRepository.java:256-300` + `Resolver.java:74-92`
**Category:** redundant-io

A locally-cached artifact is fully re-read to recompute its integrity hash (SHA-512 vs the sidecar), then `Resolver.materialize` → `validate` re-reads and re-hashes the same file with the pinned algorithm. Two full passes over every artifact's bytes each time the `Dependencies` step runs (~1 GB of hashing for ~500 MB of deps).

**Fix:** compute all required digests in one streaming pass and thread the already-verified result down so `validate` can skip the redundant re-read when the algorithm matches.

### PERF-3 — [Medium] `moduleDescriptor` opens every resolved jar serially
**File:** `maven/MavenPomResolver.java:87-96` + `maven/MavenModuleResolver.java:90-100`
**Category:** cpu / redundant-io

`PathPlacement.moduleDescriptor` (which opens a `JarFile` with signature verification and reads `module-info.class`) is invoked inside a sequential `forEach` over every resolved artifact — thousands of serial jar opens on a resolution that already fetched everything in parallel.

**Fix:** parallelize the descriptor extraction over the executor, mirroring `Resolver.materializeAll`.

### PERF-4 — [Medium] OSV advisory details fetched serially
**File:** `step/OsvDownload.java:52-58`
**Category:** parallelism

Per-vulnerability detail lookups are issued one-at-a-time (`get(endpoint.resolve("/v1/vulns/" + id))`), each a full round-trip with a 30 s read timeout, while the executor sits idle. Dozens–hundreds of advisories dominate the scan step.

**Fix:** dispatch the per-id GETs concurrently on the executor and join.

### PERF-5 — [Low/Med] Quadratic license lookup + whole-file reads
**File:** `step/LicenseCheck.java:358-384` (per-dependency at `:84`); jar reads at `:282,:333`
**Category:** cpu / memory

`licenses(...)` rescans the entire `licenses.properties` set once per dependency (O(N·M)); embedded SBOM/LICENSE files are `readAllBytes()` (unbounded for a hostile jar). **Fix:** pre-bucket properties by license-key prefix once; cap/stream the embedded reads.

### PERF-6 — [Low/Med] SBOM document serialized twice per emit
**File:** `CycloneDx.java:80-86`
**Category:** cpu

The whole JSON/XML is built once (with a null serial number) solely to hash it into a UUID, then rebuilt with the serial embedded. **Fix:** build the body once, compute the UUID, splice the serial field in.

### PERF-7 — [Low/Med] Per-module `.tsv` re-downloaded on every `fetch`
**File:** `module/JenesisRawGitRepository.java:182-195`
**Category:** redundant-io

No memoization; the same module fetched for different types/classifiers re-downloads its TSV. **Fix:** memoize parsed TSV rows per instance for the duration of a resolution (as `MavenDefaultVersionNegotiator` caches metadata).

### PERF-8 — [Low] `ProjectWatch` never cancels WatchKeys for deleted directories
**File:** `project/ProjectWatch.java:36-38,52-66`
**Category:** unbounded-growth

Keys are only added; `key.reset()`'s `false` (invalidated) return is ignored and nothing calls `key.cancel()` on `ENTRY_DELETE`. Bounded in practice (build-output churn lives under excluded dirs), but source-tree directory churn leaks keys over a long watch session.

### PERF-9 — [Low] XML factories instantiated per call
**File:** `maven/MavenProject.java:384-388` + `CycloneDx.java:211-213,250`
**Category:** cpu

`DocumentBuilderFactory`/`TransformerFactory` (JAXP provider discovery) are newed up per call, unlike `MavenPomResolver`/`MavenDefaultVersionNegotiator` which cache theirs. Minor at realistic module counts. **Fix:** hoist to a reused factory.

> Also noted (accepted trade-off): `MavenProject`'s POM `Scan.shouldRun` always returns `true`, so the full project-tree walk runs every build. Intentional (to detect new POMs), but it is the per-build floor cost and scales with tree size.

---

## 4. I/O, network & robustness

### IO-1 — [High] No connect/read timeouts on the central fetch path
**File:** `Repository.java:125` (in `open()`, 110-172)
**Category:** network-robustness

**Verified.** `current.toURL().openConnection()` sets neither `setConnectTimeout` nor `setReadTimeout`, leaving both at the JVM default of infinity. A server that accepts the TCP connection but never sends bytes hangs the build forever with no diagnostic. A repo-wide grep confirms timeouts are set only in `OsvDownload` and `BuildExecutorHttpCache`, never here. Worse, the `catch (SocketException | SocketTimeoutException)` retry arm (165-170) is **dead code**: with no read timeout a `SocketTimeoutException` is never thrown, so the slow-read retry/backoff can never fire.

**Fix:** set `http.setConnectTimeout(...)` and `http.setReadTimeout(...)` (configurable via `-Djenesis.repository.*timeout`, mirroring `OsvDownload`'s 10 s/30 s). The existing timeout-retry loop then becomes live.

### IO-2 — [Medium] Unknown-severity vulnerabilities silently bypass the `--fail-on` gate
**File:** `step/OsvDownload.java:140-146` → `step/VulnerabilityCheck.java:82-85,132-141`
**Category:** silent-failure

An advisory whose severity Jenesis cannot score (CVSS 4.0 is explicitly left unscored, returning `-1`; or any unrecognized band) is written with an empty severity string. In `VulnerabilityCheck`, `severity("")` throws `IllegalArgumentException` and returns `null`; the `failOn` comparison is guarded by `severity != null`, so the advisory is listed in the report but never added to `violations`. A build configured `failOn(HIGH)` **passes despite a real critical finding**.

**Fix:** treat empty/unknown severity as a distinct state that fails (conservative) or emits a loud warning even when `warn` is false — never as "not a violation."

### IO-3 — [Medium] Eager-stream repository items leak sockets / break re-reads
See **BUG-10** above (`JenesisModuleRepository` / `JenesisRawGitRepository`). Cross-listed here because the fix (deferred open) is also the robustness fix.

### IO-4 — [Medium] OSV queries have no retry/backoff and leak the error stream
**File:** `step/OsvDownload.java:278-308`
**Category:** network-robustness

A transient 429/5xx throws immediately and fails the whole vulnerability step; the non-200 path never drains/closes `getErrorStream()`, so the keep-alive socket is not returned to the pool. Unlike `Repository.open`, there is no retry loop.

**Fix:** honor 429/`Retry-After` with bounded backoff (reuse `Repository.Retry`), and drain+close the error stream before throwing.

### IO-5 — [Low] `DownloadModuleUris` writes its output non-atomically
**File:** `module/DownloadModuleUris.java:39-51`
**Category:** temp-cleanup

`uris.properties` is written in place while streaming multiple locations; a mid-stream fetch failure leaves a truncated file in the step output. (It also uses the timeout-less `Repository.open` — see IO-1.) **Fix:** write to a temp file and `ATOMIC_MOVE` after all locations succeed.

### IO-6 — [Low] `Retry-After` HTTP-date form ignored; backoff shift can overflow
**File:** `Repository.java:145-159`
**Category:** network-robustness

Only delta-seconds `Retry-After` is parsed; the legal HTTP-date form is swallowed by the empty catch. Separately, `backoff().toMillis() << attempt` can overflow to a non-positive delay at a very high configured retry count. **Fix:** parse the date form; clamp the shift.

---

## 5. Usability

### USE-1 — [Medium] Every failure is rewrapped as a "pass `help`" hint; all exit codes flattened to 1
**File:** `Project.java:2232-2251`
**Category:** usability-errormsg / exit-codes

`main` catches `Throwable` and rethrows `new UsageHint(t)`, so a compile error, a network timeout, and "docker not installed" all surface as "Pass `help` … for usage information" — implying the user mistyped a command when they did not — and the JVM always exits 1. This is inconsistent with `Jpx.main`, which uses `64` (EX_USAGE) for bad arguments and `0` for `--help`. The three CLIs (`Project`, `Execute`, `Jpx`) disagree on exit-code conventions.

**Fix:** emit the usage hint only for genuine argument/usage errors; print the underlying message/stack for other throwables; map categories to distinct exit codes aligned with `Jpx`'s `64`-for-usage convention.

### USE-2 — [Low] `jpx --docker=` (empty value) passes an empty image to docker
**File:** `Jpx.java:210-212,237-239`
**Category:** usability-cli

A trailing `--docker=` yields a non-null empty `image`, taking the explicit-image branch and producing `docker run … "" …` with an opaque error instead of the intended implicit hardened image. **Fix:** treat a blank value as "no image" (fall back to the default) or reject with a clear message.

### USE-3 — [Low] `Retry-After`/timeout misconfig surfaces confusingly
Covered by IO-1/IO-6 — a hung fetch with no timeout gives the user no signal at all; worth a user-facing "still trying <uri>" diagnostic once timeouts exist.

### USE-4 — [Low] Windows bind-mount construction is fragile
**File:** `docker/DockerizedJava.java:167-173`
**Category:** portability

`-v host:container` is built by naive concatenation; on a Windows daemon a drive-letter path like `C:\opt\java-home` collides with docker's `host:container:mode` colon semantics. `Mount.parse` carefully handles the drive-letter colon on the *input* side, but the `-v` emission does not. **Fix:** use `--mount type=bind,src=…,dst=…,readonly`.

### USE-5 — [Low] Orphaned `jpx` lock files accumulate
**File:** `Jpx.java:336-343`
**Category:** temp-cleanup

Each versioned install leaves a `<name>@<version>.lock` under the storage root forever. Cosmetic (the lock correctly targets a separate file from the installation), but `~/.jenesis/jpx` fills over time. **Fix:** sweep orphaned `*.lock` opportunistically, or document as intentional.

---

## 6. Test gaps

Grouped by subsystem; **High** = an untested security guard, checksum-enforcement path, or core resolution/cache-invalidation correctness. Each entry names the nearest existing coverage so the delta is clear.

### Resolution
- **TEST-R1 [High]** Root `dependencyManagement`/BOM overriding a *purely transitive* dependency is untested (`MavenPomResolver.traverse:297`). All management tests manage a *direct* dep. *Suggest:* root declares `other:1` + management pinning `transitive→2`; `other→transitive:1` ⇒ expect `transitive=2`.
- **TEST-R2 [High]** SNAPSHOT resolution is entirely untested and arguably unimplemented — `MavenDefaultVersionNegotiator.toMetadata` parses only `latest`/`release`/`versions`, no `<snapshotVersions>`/timestamp handling; a `-SNAPSHOT` dep falls through unchanged and that pass-through is never exercised. *Suggest:* resolve a dep declared `…-SNAPSHOT` and assert the pass-through value (documents the no-timestamp behavior).
- **TEST-R3 [High]** Partial wildcard exclusions `group/*` and `*/artifact` untested (`traverse:289-290`); only exact `g/a` and full `*/*` are covered.
- **TEST-R4 [Med]** `provided`/`runtime` transitive scope propagation untested (`traverse:309-312`); only `test`-scope drop is covered.
- **TEST-R5 [Med]** Direct (root) `optional=true` dependency retention untested (`traverse:301` `!root()` guard).
- **TEST-R6 [Med]** Exclusion accumulation across ≥2 transitive levels untested (`traverse:363-367`).
- **TEST-R7 [Med]** Malformed range syntax and missing/unknown metadata throws untested (`parseRanges:139/146/166`, `toMetadata:200/232`). There is no `MavenVersionNegotiatorTest` at all to host `parseRanges` unit tests.
- **TEST-R8 [Low]** Range interior-whitespace trimming (`[ 1 , 2 ]`) never load-bearing.

### Repository security
- **TEST-S1 [High]** `Repository.open` credential-scoping on cross-origin redirect is untested — every `RepositoryTest.open_*` passes `token=null`, so the `sameOrigin` guard that suppresses `Authorization` after a redirect to another host is never exercised. *Suggest:* A (302→B), `open(uriA, "Bearer secret")`; assert B receives no `Authorization`, A does. Security-critical.
- **TEST-S2 [High]** `Repository.open` insecure-scheme rejection untested — every test sets `-Djenesis.repository.insecure=true`, so the `http`-refusal branch never fires. *Suggest:* `open(http://…, null)` with the property unset ⇒ `IllegalStateException` "insecure scheme".
- **TEST-S3 [Med]** `JenesisRawGitRepository.requireSafeSegment` blank-segment rejection untested (the sibling `JenesisModuleRepositoryTest.rejects_blank_classifier` exists — port it).
- **TEST-S4 [Low]** `requireSafeSegment`'s `..` rejection is only reached via the version role; add module-name/classifier `..` inputs.

### Module alias (new feature)
- **TEST-A1 [High]** Chained alias (a target that is itself an alias) — no test; behavior undefined. *Suggest:* define expected resolution or an explicit rejection.
- **TEST-A2 [Med]** Alias + exclusions interaction untested (does the "no exclusions" rejection surface through the alias wrapper?).
- **TEST-A3 [Med]** Two sibling modules aliasing the same name to *different* targets — cross-module collision at the project level is untested (only the within-one-module duplicate is). *Suggest:* pin the documented "tolerated, unified at meeting point" behavior with a test.
- **TEST-A4 [Med]** `MavenModuleResolver`/`MavenAliasResolver` alias-without-wrapper and malformed-target/version resolver-level guards untested (parser-level equivalents are covered).
- **TEST-A5 [Low]** Parser branches: alias name containing `/`, version containing `/`; classified target + BOM override combined; alias in a pure MODULAR layout at the project/build level (only resolver-unit rejection exists).

### Jpx
- **TEST-J1 [High]** Concurrent-install lock contention (`install:335-343`, inner double-check, staging discard) never raced — the single-threaded test always takes the inner `if`.
- **TEST-J2 [High]** Non-Maven (`module`/`modular`) streaming spill branch (`spilling(Repository)`) is dead in tests — only the Maven spill variant is covered by `installs_without_local_maven_repository`.
- **TEST-J3 [High]** `spill()` filename-collision `FileAlreadyExistsException` catch and `Throwable` cleanup untested.
- **TEST-J4 [High]** Docker `:ro` mount is only exercised by `launches_in_docker`, which is `@EnabledIf(dockerAvailable)` (skipped here) and never asserts the read-only mount. *Suggest:* a daemon-free assertion on the built command list.
- **TEST-J5 [Med]** `Jpx.main` CLI parsing (options, exit-64 paths); the `--add-modules ALL-MODULE-PATH` branch; mixed modulepath+classpath launch + `verify()` over both; `requireSafeSegment` traversal/illegal-char via install; `DockerizedJava.command()` mount/env/user/JAVA_HOME wiring.
- **TEST-J6 [Low]** `Command.parse` malformed guards; install internal-consistency throws; `latestInstalled` missing-storage-dir branch.

### Cache (incremental invalidation)
- **TEST-C1 [High]** No test proves a *changed* input hash produces a cache **miss** and does not materialize a stale output — the whole content-addressed correctness rests on this. Existing tests prove only cold miss and eviction.
- **TEST-C2 [High]** No executor + real-cache integration proving a changed source **re-executes** past a *populated* cache — the changed-source executor tests use `BuildExecutorCache.nop()`, so they prove only local-checksum invalidation.
- **TEST-C3 [High]** `BuildExecutorFileCache` corrupted-entry recovery and zip-slip `"Bad cache entry:"` guard are untested, though `HttpCache` has the exact analog (`corrupt_cache_entry_leaves_the_target_empty`) — port it.
- **TEST-C4 [Med]** No checksum re-verification of a cache-fetched output (a bit-flipped well-formed entry is served silently); FileCache atomic-move fallback; FileCache concurrency/idempotency.
- The **inverse** (unchanged input ⇒ reuse, no spurious rebuild) *is* well covered.

### Other
- **TEST-O1 [Low]** `Resolver.validate` with a checksum lacking `/` → `substring(0,-1)` throws `StringIndexOutOfBoundsException` instead of a clear message. Mismatch and strict-pin paths *are* covered at the `DependenciesResolutionTest` integration level.
- **TEST-O2 [Low]** `Pinning.fromProperty` invalid-value path untested.

### Entirely-untested classes (meaningful logic, zero coverage)
`project/PiTestModule.java` (269 L), `project/JenesisClassLoaderBridge.java` (325 L), `project/LauncherModule.java` (89 L), `module/DownloadModuleUris.java` (54 L). Additionally `BuildExecutorDefault.java` (639 L) has no dedicated test — heavily exercised indirectly, but its cache-specific branches (async store, `RejectedExecutionException` swallow at ~211, `cache.touch` on up-to-date at ~238, `supplement()`) have no targeted assertions. The TestNG/JUnit4/JUnitPlatform engine adapters are referenced but have no dedicated adapter tests.

---

## Appendix — verified sound (checked, not flagged)

- **Path/coordinate validation on the core resolve path:** `MavenDependencyKey.validate` rejects `/`, `\`, `.`, `..` in every component; negotiated versions reach the `.m2` write only through `MavenDefaultRepository.fetch`'s escapes-root check; `Jpx` install validates name/group/artifact/version and the negotiated version via `requireSafeSegment`, and the spill filename is built from validated components.
- **Zip-slip:** both cache extractors (`BuildExecutorFileCache.unzip`, `BuildExecutorHttpCache.unzip`) have a correct `destination.normalize().startsWith(base)` guard. *(Minor: `BuildExecutorHttpCache.unzip` does not close its `ZipInputStream` — a small stream leak, unlike the FileCache sibling which uses try-with-resources.)*
- **Credentials & SSRF:** `Repository.open` restricts schemes to `https`/`file`, follows redirects by hand, and sends the `Authorization` token only when `sameOrigin` holds; `BuildExecutorHttpCache.connect` refuses to send the cache key over non-https/non-loopback.
- **Command injection:** every `ProcessBuilder` (`Jpx`, `DockerizedJava`, `ProcessHandler`, `Execute`) passes an argument list — no shell — with build-owner-controlled inputs.
- **Deserialization:** `Serializable` is used only write-only for digesting build steps; there is no `ObjectInputStream`/untrusted deserialization anywhere in the tree.
- **Module-alias feature:** `MavenAliasResolver.rename()` correctly strips synthetic coordinates from artifacts, edges, and vertices; the empty-jar `computeIfAbsent` is race-safe; `internal=true` yields the version-less `module/<alias>` entry; version precedence pin→inline→RELEASE holds; published POMs flatten the alias to its target.
- **Concurrency:** `Resolver.materializeAll` / `HashFunction.hashAll` parallelize over the executor with a `ConcurrentHashMap` and per-task `CompletableFuture`s correctly; the version negotiator's plain `HashMap` cache is touched only from the single-threaded traversal.
- **Build DAG:** `BuildExecutorDefault.doExecute` topological dispatch and exception propagation have no dispatch deadlock (preliminaries are pulled into `scheduled`); `SequencedProperties` round-trips deterministically; `BuildExecutorLayeredCache` layering and `Checksum.diff` status derivation are sound.
- **Streaming:** the artifact download path (`MavenDefaultRepository.download`, `HashDigestFunction`) streams via a 64 KB buffer and never slurps jars into a `byte[]`; `Pattern`s are hoisted to `static final` except the intentionally per-spec user-supplied test patterns.

---

## Suggested remediation order

1. **BUG-1** (checksum bypass) and **SEC-1/SEC-2** (traversal) — integrity/security, small localized fixes; add **TEST-S1/S2** and a traversal-rejection test alongside.
2. **IO-1** (network timeouts) — one-line-per-connection fix that also revives dead retry logic; high blast-radius reliability win.
3. **BUG-4** (stale resources) — correctness of incremental builds; add a resource-only-change regression test.
4. **IO-2** (vuln gate bypass) — security-tooling trust.
5. **PERF-1** (test-selection OOM) — scalability ceiling.
6. **BUG-5/BUG-6/BUG-2** and the cache-invalidation tests (**TEST-C1/C2/C3**) — correctness hardening.
7. Remaining Low/Med items and test-gap backfill as capacity allows.
