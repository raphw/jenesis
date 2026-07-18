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

---

# Deep dive: Incremental compilation

A dedicated follow-up pass over the change-detection engine and every step that overrides `shouldRun`, aimed specifically at the class of bug where **a change that should trigger a recompile/re-run does not, so a stale artifact is served**. Findings verified against the source.

## How incremental works (context for the findings)

`BuildExecutorDefault` re-runs a step when **`!consistent || shouldRun(arguments)`** (`BuildExecutorDefault.java:143`):

- **`consistent`** is false when the step's **serialized form** changed (`BuildStepHashFunction` digests the step's field values + `serialVersionUID`) **or** when `HashFunction.areConsistent` finds the step's own prior output was altered/partial. So *configuration held as a step field is auto-tracked* — changing it forces a rerun.
- **`shouldRun(arguments)`** inspects `BuildStepArgument.hasChanged(prefix…)` — true when a tracked **input file** under a declared path prefix changed. The **default** `shouldRun` returns true if *any* input changed (safe). The risk lives entirely in the **43 `shouldRun` overrides**: an override that declares a *narrower* prefix list than what `apply()` actually reads will **miss** a change to an undeclared input and serve a stale output.

Cross-module invalidation is propagated by `MultiProjectDependencies.fingerprint` (`:124-133`), which writes a **sibling's built-artifact content checksum** into the dependent's `REQUIRES` value — so a rebuilt dependency changes that value → the dependent re-resolves and recompiles. This mechanism is correct **but only by convention** (see INC-10).

## Correctness findings (stale-output bugs)

### INC-1 — [High] Resource-only edits not recompiled in single-compiler modules *(= BUG-4, now confirmed across all four compilers)*
`Javac.java:88-105` (`hasRelevantChange`); wiring `InferredCompilerChainModule.java:176`. Confirmed identical in `KotlinCompilerModule`, `ScalaCompilerModule`, `GroovyCompilerModule` — all four route through the same `hasRelevantChange` helper, which flags a change under `sources/` only for a *source-extension* leaf. When `compilers == 1` the standalone `Resources` step is not added and the compiler copies resources itself (`includeResources(true)`), so editing only a resource serves a stale resource in the jar. See BUG-4 for the fix.

### INC-2 — [High] `Sbom` omits `licenses.properties` and `graph.properties` — stale SBOM served
`step/Sbom.java:44-48` (`shouldRun` declares only `DEPENDENCIES`, `METADATA`) vs `Sbom.java:65,74,171` (reads `graph.properties` and `licenses.properties`). **Verified.** Editing `spdx.properties` (a license alias/category) makes `Dependencies` rewrite `licenses.properties` — but SPDX data feeds only the licenses output (`Dependencies.java:396-406`), never the `dependencies.properties` index, so `DEPENDENCIES` is byte-identical and `Sbom.shouldRun` returns false. The embedded `META-INF/sbom/*` (baked into the jar) and the standalone `reports/sbom/*` are served with **stale license data**. **Fix:** add `Path.of("licenses.properties")` and `Path.of("graph.properties")` to `hasChanged(...)`.

### INC-3 — [High] `Versions` omits `manifest.mf` — stale published jar manifest
`step/Versions.java:16-20` (`shouldRun` declares only `DEPENDENCIES`, `CLASSES`) vs `Versions.java:48-52,69` (collects and merges each argument's `manifest.mf`, which sits at the folder root, not under `classes/`). **Verified.** A changed manifest with identical `.class` bytes is skipped → the published jar ships a **stale manifest** (`Main-Class` / `Automatic-Module-Name` / custom attributes). **Fix:** add `Path.of("manifest.mf")` to `hasChanged(...)`.

### INC-4 — [High] Step *code*/lambda-body changes never invalidate outputs (the `serialVersionUID` footgun)
`BuildStepHashFunction.java:10-34`, used at `BuildExecutorDefault.java:116-123`. The step digest hashes serialized **field values + `serialVersionUID`**, never bytecode — a fact the tool's own help text admits (`Project.java:857-874`: "Changes to a build step's *code* … do NOT alter the serialized form, so cached outputs are NOT invalidated. After such an edit, bump the step class's `serialVersionUID`"). Editing the body of any `apply(...)` without touching a field → stale outputs served indefinitely. **Worse for lambda/`Serializable`-predicate steps** (e.g. `MultiProjectDependencies(P isModule)`, `MultiProjectDependencies.java:14`): a serialized lambda captures only its method reference + captured args and **cannot carry a `serialVersionUID`**, so a body edit is silently un-invalidatable *in principle*. This is a footgun, not just a missing feature. **Fix:** mix a build-of-the-tool identity (implementing class's compiled-bytecode hash, or a per-release build stamp) into the step digest so a recompiled step body changes the hash automatically.

### INC-5 — [Medium-High] `Dependencies`' `transient repositories` field is invisible to *both* invalidation channels
`step/Dependencies.java:23` (`private final transient Map<String, Repository> repositories;`) and `shouldRun` at `:80-89`. **Verified** the field is `transient`. Changing a repository URL, adding/removing a repository, or swapping credentials/mirror changes neither the tracked input files (so `shouldRun` is false) nor the serialized step form (the field is `transient`, so the step hash is unchanged). Resolution is **not** re-run — the build keeps resolving against the old repository set. **Fix:** serialize a stable descriptor of the repository set (names + URLs + layout) into the step hash, or feed a `repositories` fingerprint into the tracked inputs.

### INC-6 — [Medium] Dependency & annotation-processor jars live under `resolved/`, which `hasRelevantChange` doesn't monitor
`Javac.java:88-90` monitors `classes/`, `artifacts/`, `dependencies.properties` — **not** `resolved/`, where `Dependencies` actually places jars (`Dependencies.java:439-440`). **Verified.** Change-detection for a dependency jar therefore relies entirely on the index *value* string changing. For a **pinned** dep the value is a content checksum (safe) and for an **internal sibling** it is the propagated fingerprint (safe), but for an **unpinned external dep** it is the bare version string: an in-place same-version byte change (a `-SNAPSHOT` republished, or an annotation processor swapped at the same coordinate) leaves the index identical → dependents compile against stale classes / stale generated code. The resolution gate is blind too (INC via A3): an unpinned `-SNAPSHOT` in `REQUIRES` is byte-identical across a republish, so `Dependencies` doesn't even re-fetch. **Fix:** add the `resolved/` prefix to `hasRelevantChange`, and/or encode the artifact content checksum (not the version string) into the `dependencies.properties` value for unpinned artifacts.

### INC-7 — [Medium] `Versions` module-info reuse gated on `REQUIRES` but the injected version comes from `DEPENDENCIES`
`Versions.java:73-76` computes `requiresChanged` from the `REQUIRES` status; `:93-101` copies the previously-transformed `module-info.class` when `!requiresChanged`. But the version values injected into `requires` are built from the `versions` map derived from `DEPENDENCIES` (`:33-45`, applied `:114`). A transitive/managed version resolving differently changes `DEPENDENCIES` (the step *does* run), yet with `REQUIRES` unchanged the fast-path copies the stale transformed module-info carrying the **old injected `requires` version**. **Fix:** base the reuse guard on the `DEPENDENCIES` status (or whichever files feed the `versions` map), not `REQUIRES`.

### INC-8 — [Medium] `Sbom`/`Bom` hash an internal jar's content but don't declare its folder
`Sbom.java:89-91` (component SHA-256) and `Bom.java:64-66` (BOM checksum when the index carries none) hash the referenced jar directly, but their `shouldRun` (`Sbom.java:44`, `Bom.java:22`) watch only `DEPENDENCIES`/`METADATA`/`MODULE`. For an internal/SNAPSHOT dep whose `DEPENDENCIES` entry has an empty checksum, a rebuilt jar leaves the index text identical → the published SBOM component hash / `bom-<module>.properties` checksum is stale. Same root cause as INC-6. **Fix:** include the `resolved/` prefix, or only trust the index when every entry already carries a checksum.

### INC-9 — [Medium] Inventory omits `IDENTITY` *(= BUG-6, confirmed independently)*
`step/Inventory.java:30-48` declares many prefixes but not `IDENTITY`, though `apply` reads `identity.properties` (`:111-113`) and republishes it as `module.identity.N` (`:182-184`). An `Assign`-rewritten coordinate with all else unchanged → stale inventory feeds PinPom/PinModuleInfo/MavenProject. **Fix:** add `Path.of(IDENTITY)`.

### INC-10 — [Medium] Cross-module invalidation is correct only by convention (prefix whitelist + empty-checksum fallback)
`project/MultiProjectDependencies.java:19-28` (`shouldRun` whitelists `REQUIRES/VERSIONS/ALIASES/BOMS/EXCLUSIONS/IDENTITY/ARTIFACTS`) and `:124-133` (`fingerprint`). Propagation is only as fresh as `shouldRun` lets the step re-run: it works **because** built jars happen to land under `artifacts/` (`Assign.java:46`, `Bundle`, `JLink`, …) which is whitelisted — an invariant enforced by convention, not the engine. Any future step that fingerprints an artifact placed outside those prefixes silently breaks downstream invalidation. Additionally the `candidate == null` fallback (`:97-98`) writes a `REQUIRES` entry with **no checksum suffix**, so content changes for such an entry never propagate. **Fix:** drop the whitelist (fall back to the generic `hasChanged`), or assert every fingerprinted coordinate's artifact lies under a tracked prefix.

### INC-11 — [Low] Non-atomic output commit
`BuildExecutorDefault.java:180-199`: the commit is delete-then-move-then-write-checksums, not a single rename of a fully-staged tree. A crash between the move and writing `output.properties`/`step.properties` leaves the new output live with an empty `checksum/` (recoverable — the next run sees the missing `step.properties`, sets `consistent=false`, rebuilds), and a crash after deleting `previous` but before the move loses the previously-good output. Safe, not atomic. **Fix:** stage `checksum/` inside the staging folder and make the publish a single directory rename.

## Verified sound (incremental)
- **Cross-module propagation** works for the common cases (sibling fingerprint → `REQUIRES` → re-resolve → recompile), and pinned deps are safe.
- **`areConsistent`** (`HashFunction.java:92-108`) correctly rejects extra/altered/missing files in a prior output; the `~` staging folder is a sibling of `output`, never nested, and is deleted before reuse (`encode` emits `%7E`, no collision).
- **`shouldRun` diffing is deterministic/key-based** (`Checksum.diff` is pure map operations over sorted `output.properties`) — unlike the *cache-key* fold, which is filesystem-order-dependent (see the determinism item under §2 / INC-B1).
- **Release target, `process-<tool>.properties` extra args, inter-compiler `.kt`↔`.java`, JDK toolchain version, source deletions** are all correctly tracked (serialized field, declared prefix, or `classes/` prefix as applicable).
- Steps confirmed correctly scoped: `Bind`, `Assign`, `Dependencies`, `Group`, `MultiProjectDependencies` (modulo INC-10), `Pom`, `Tree`, `PinPom`, `PinModuleInfo`, the export steps, and the always-run `MavenProject` scans.

## Improvement roadmap (incremental compilation)

Ordered by value-to-effort:

- **INC-B1 [Low effort, high value] Make the cache key deterministic.** `BuildExecutorFileCache.fold` (`:158-175`) digests each argument's checksum map in filesystem-walk order (`HashFunction.read`→`files()` is an unsorted `DirectoryStream`), so identical inputs produce different cache keys across machines and shared/remote cache hit-rate collapses. Sort by path in `fold` (mirroring `HashFunction.write:87`). *(Same root cause as the determinism item in §2.)*
- **INC-B2 [Low effort, high value] Close the two monitoring gaps.** Add `resolved/` to `hasRelevantChange` (fixes INC-6) and widen the single-compiler `shouldRun` to react to resource changes when `includeResources` is set (fixes INC-1) — two one-to-few-line edits that make incremental builds materially safer.
- **INC-B3 [High value, higher effort] ABI-based compile avoidance.** Today any content change to an internal dependency's jar (`Javac.java:90` `dependencies.properties` trigger) forces a full recompile of every dependent — even a javadoc-only, resource-only, or private-body change. Fingerprint compile-scope internal deps by a **public-ABI hash** (exported type/signature surface) and skip dependents when the ABI is unchanged. This is the single biggest multi-module speedup available.
- **INC-B4 [Medium] Per-file incremental compilation.** The engine already computes a precise per-file `ADDED/ALTERED/REMOVED` diff (`Checksum.diff`) and threads the prior output into the step, but `Javac.shouldRun` collapses it to one boolean and `apply` recompiles the whole module. Pass the changed-source set into `Javac.apply` and drive `javac`'s file list from `context.previous()` so touching one file recompiles one file.
- **INC-B5 [Medium] Short-circuit `areConsistent`'s full re-hash.** On every build each up-to-date step re-walks and re-hashes its *entire* prior output (`HashFunction.areConsistent:101`); a no-op build of a large project re-hashes all outputs. Record per-file size+mtime at commit and skip re-hashing unchanged files. Big win for no-op/small builds.
- **INC-B6 [Medium] Warm compiler / in-process javac.** Every compile forks a JVM; Kotlin/Scala/Groovy fork `bin/java` + a compiler main per module per build (`KotlinCompilerModule.java:163,257-263`) and pay full compiler init each time. Use a persistent compiler daemon (Kotlin compile daemon / Nailgun) and/or in-process `javac` via `ToolProvider`/`JavaCompiler` (`Javac.java:22-24` always spawns) — the latter also unlocks INC-B4.
- **INC-B7 [Medium] Avoid double-compiling `.java` in mixed modules.** In a Kotlin+Java (or Scala+Java) module, kotlinc/scalac joint-compiles the `.java` files (`KotlinCompilerModule.java:224-227`) *and* the downstream `Javac` step recompiles the same sources — duplicate work merged by `Versions`. Feed `.java` to kotlinc/scalac as classpath stubs, or make `javac` authoritative.
- **INC-B8 [Low] Executor scheduling.** `doExecute` uses an O(n²) ready-set rescan (`:484-537`) and serial `thenCombineAsync` fan-in folds (`:538-545`); step *execution* is already parallel, but scheduling/fan-in serialize on wide graphs. Replace with a dependency-count ready-queue + `allOf`.

> Note: cross-module compile parallelism already exists (independent steps run concurrently via `CompletableFuture` in `BuildExecutorDefault`); the within-module compiler chain is serial by dependency and needs INC-B7-style restructuring to change.
