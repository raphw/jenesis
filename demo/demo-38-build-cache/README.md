Shared build cache demo
=======================

Every build already has an *incremental* cache: Jenesis content-hashes each
step's inputs and outputs under `target/`, so a warm rebuild only re-runs the
steps whose inputs changed. This demo adds the second tier - a **shared build
cache** outside `target/` that can be handed step outputs from an earlier build
(or a different machine, or CI) instead of re-running the step at all.

It is opt-in with one system property pointing at a folder:

    -Djenesis.executor.cache=<folder>

When set, `BuildExecutor.Configuration` resolves a `BuildExecutorFileCache` at
that folder; unset, the cache is a no-op and nothing changes. The folder is
content-addressed: each entry lives at `<folder>/<step-hash>/<inputs-hash>/`,
where the step hash identifies the step (its serialized form) and the inputs hash
folds every input file's content hash. On a local miss the executor asks the
cache for that coordinate; a hit materializes the cached output (hard-linked, so
it is near free) and the step body never runs.

Build it
--------

This is the smallest possible project - a `pom.xml` and one dependency-free
source - so the only thing of interest is *who* produced the output. Run it from
this directory.

**1. Bootstrap the cache with a valid entry.** A normal build populates the
folder while it compiles:

    java -Djenesis.executor.cache=.jenesis/build-cache build/jenesis/Project.java

    [EXECUTED]  .../compile/javac in 0.07 seconds
    [EXECUTED]  .../binary/classes in 0.02 seconds
    [EXECUTED]  .../binary/artifacts/jar in 0.02 seconds
    [COMPLETED] Finished in ...

The cache now holds one entry per step under `.jenesis/build-cache`.

**2. Force a full rebuild, served from the cache.** `-Djenesis.executor.rebuild=true`
deletes `target/` before the build, so the incremental cache is gone and *every*
step is a forced miss that would normally re-run from scratch. The shared cache
lives outside `target/`, so it survives - and serves them:

    java -Djenesis.executor.cache=.jenesis/build-cache \
         -Djenesis.executor.rebuild=true \
         build/jenesis/Project.java

    [EXECUTED]  .../compile/javac in 0.00 seconds
    [EXECUTED]  .../binary/classes in 0.00 seconds
    [EXECUTED]  .../binary/artifacts/jar in 0.00 seconds
    [COMPLETED] Finished in ...

The steps still print `[EXECUTED]` - their output *was* produced - but it came
from the cache, not from `javac`, which is why each lands in ~0.00s. On this toy
project the absolute saving is tiny; on a real module the compile step that took
seconds returns instantly. Delete `.jenesis/build-cache` to start over.

Layout
------

    demo/demo-38-build-cache
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              Maven coordinates and <sourceDirectory>, no dependencies
    `-- sources/sample/Sample.java

There is nothing build-cache-specific in the project itself: the cache is an
engine capability you switch on from the command line, so any project gains it
for free.

Tuning with `cache.properties`
------------------------------

Drop an optional `cache.properties` at the cache root to tune the writes; every
key has a default, so the file may be omitted entirely:

| key          | default   | effect                                                                 |
| ------------ | --------- | ---------------------------------------------------------------------- |
| `digest`     | `SHA-256` | algorithm folding the inputs into the entry-folder name                |
| `steps`      | `250`     | maximum number of step folders kept                                    |
| `versions`   | `10`      | maximum input-variants kept per step                                   |
| `size`       | unset     | maximum total bytes kept; over it, whole entries are evicted by `lru` until under (unset = no size cap) |
| `lru`        | `true`    | evict the least-recently-updated entry when over a limit (`false` = most-recently) |
| `touch`      | `true`    | bump an entry's timestamp on read, so reads keep hot entries alive     |
| `compressed` | `false`   | store each entry as a single zip file rather than a folder of files    |
| `read`       | `true`    | serve cache reads; `read=false` makes every lookup a miss              |
| `write`      | `true`    | populate the cache; `write=false` serves reads but never writes, evicts, or touches |

Eviction is by file timestamp, performed on write; `touch` keeps recently-read
entries fresh so the count caps (`steps`, `versions`) and the byte cap (`size`)
approximate an LRU. `compressed` trades the hard-linked
reads of the folder format for a packed, transport-friendly layout, approaching
the shape a remote cache server would store. `read` and `write` are the typical CI
split: a privileged job builds with the defaults (both `true`) to populate the
cache, while everyone else sets `write=false` to consume it read-only without
mutating it. Setting both to `false` turns the cache off entirely.

Where this fits
---------------

`BuildExecutorFileCache` is one implementation of the pluggable
`BuildExecutorCache` seam; a remote backend is just another implementation of the
same `fetch`/`store` interface, wired the same way. The local folder cache here is
the on-disk analogue: point several checkouts (or a CI workspace and your laptop)
at a shared folder and a step compiled once is reused everywhere its inputs are
identical.

Giving `-Djenesis.executor.cache` an `http://` or `https://` URL instead of a
folder selects `BuildExecutorHttpCache`, the networked sibling that GETs and PUTs
the same zip entries to any compatible HTTP cache server, authenticating with
`-Djenesis.executor.cache.key=<key>`:

    java -Djenesis.executor.cache=https://cache.example.com \
         -Djenesis.executor.cache.key=team-alpha \
         build/jenesis/Project.java
