Build cache demo
================

Every build already has an *incremental* cache: Jenesis content-hashes each
step's inputs and outputs under `target/`, so a warm rebuild only re-runs the
steps whose inputs changed. This demo adds the second tier - a build cache
*outside* `target/` that can hand a step the output of an earlier build (or a
different checkout, machine, or CI) instead of re-running it at all.

That cache can live in two places - a project-local folder, or a shared location
you name - and the two compose.

A project-local cache (the easy one)
------------------------------------

The simplest form needs nothing but a flag; Jenesis keeps a content-addressed
cache under `.jenesis/cache`, rooted at the project root:

    -Djenesis.project.cache

The property is a filesystem path; an empty value (as above) resolves to
`.jenesis/cache` under the project root, and a value relocates it. This is the
main example below. Each entry lives at
`.jenesis/cache/<step-hash>/<inputs-hash>/`, where the step hash identifies the
step (its serialized form) and the inputs hash folds every input file's content
hash. On a miss the executor runs the step and stores the result; on a hit it
materializes the cached output (hard-linked, so it is near free) and the step body
never runs. Because it sits outside `target/`, it survives a `target/` wipe.

Build it
--------

This is the smallest possible project - a `pom.xml` and one dependency-free
source - so the only thing of interest is *who* produced the output. Run it from
this directory.

**1. Bootstrap the cache.** A normal build populates `.jenesis/cache` while it
compiles:

    java -Djenesis.project.cache build/jenesis/Project.java

    [EXECUTED]  .../compile/javac in 0.07 seconds
    [EXECUTED]  .../binary/classes in 0.02 seconds
    [EXECUTED]  .../binary/artifacts/jar in 0.02 seconds
    [COMPLETED] Finished in ...

**2. Force a full rebuild, served from the cache.** `-Djenesis.executor.rebuild=true`
deletes `target/` first, so the incremental cache is gone and *every* step is a
forced miss that would normally re-run from scratch. The build cache lives outside
`target/`, so it survives - and serves them:

    java -Djenesis.project.cache \
         -Djenesis.executor.rebuild=true \
         build/jenesis/Project.java

    [EXECUTED]  .../compile/javac in 0.00 seconds
    [EXECUTED]  .../binary/classes in 0.00 seconds
    [EXECUTED]  .../binary/artifacts/jar in 0.00 seconds
    [COMPLETED] Finished in ...

The steps still print `[EXECUTED]` - their output *was* produced - but it came
from the cache, not from `javac`, which is why each lands in ~0.00s. Add
`-Djenesis.print.cache` to make it explicit: each step served from the cache then
prints a `[LOADED]` line and each one written to it a `[STORED]` line. On this toy
project the saving is tiny; on a real module the compile that took seconds returns
instantly. Delete `.jenesis/cache` to start over.

A shared cache (the `uri` one)
------------------------------

`.jenesis/cache` is private to one checkout. To share results across checkouts,
machines, or CI, name an explicit location with `-Djenesis.cache.uri=`. The value
is a URI: it typically points at a cache *server* over HTTP, but it can equally be
a `file://` location on a local or shared (NFS, network drive) file system:

    -Djenesis.cache.uri=https://cache.example.com      # a cache server
    -Djenesis.cache.uri=file:///mnt/team/jenesis-cache # a shared (or local) folder

A `file://` URI resolves a `BuildExecutorFileCache` (the same on-disk format as the
local cache); an `http(s)://` URL selects `BuildExecutorHttpCache`, which GETs and
PUTs the same zip entries to the server, naming the cache project with
`-Djenesis.cache.project=<project>` and authenticating with
`-Djenesis.cache.key=<key>` (both sent as headers, never in the URL). A non-URI
value is rejected - use `file://` for an on-disk location.

The shared cache can be used **two ways**.

**As a replacement** - the shared cache only, no local tier (e.g. an ephemeral CI
runner whose disk is thrown away anyway):

    java -Djenesis.cache.uri=file:///mnt/team/jenesis-cache build/jenesis/Project.java

**Layered behind the local cache** - set both `-Djenesis.project.cache` *and*
`-Djenesis.cache.uri=...`, and Jenesis wires a `BuildExecutorLayeredCache`: every
read tries `.jenesis/cache` first and only falls through to the shared cache on a
miss; a shared hit is copied into `.jenesis/cache` on the way past, so the next
read is local; and a store writes through to both. A second build on the same
checkout never re-downloads what the first already fetched:

    java -Djenesis.project.cache \
         -Djenesis.cache.uri=https://cache.example.com \
         -Djenesis.cache.project=acme -Djenesis.cache.key=alice \
         build/jenesis/Project.java

Serving a step from the local tier means no `GET` reaches the server - which would
let that shared entry age toward eviction there even though it is in active use. So
a local hit also sends the server a best-effort `HEAD` (it never transfers the
body), and the server treats it as a read, bumping the entry's recency just as a
`GET` would. Each tier keeps its own LRU and both stay warm.

Layout
------

    demo/demo-40-build-cache
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              Maven coordinates and <sourceDirectory>, no dependencies
    `-- sources/sample/Sample.java

There is nothing build-cache-specific in the project itself: the cache is an
engine capability you switch on from the command line, so any project gains it
for free.

Tuning with `cache.properties`
------------------------------

Drop an optional `cache.properties` at the cache root - `.jenesis/cache/cache.properties`
for the project-local cache, or `<folder>/cache.properties` for a file-system
shared one - to tune the writes; every key has a default, so the file may be
omitted entirely:

| key          | default   | effect                                                                 |
| ------------ | --------- | ---------------------------------------------------------------------- |
| `digest`     | `SHA-256` | algorithm folding the inputs into the entry-folder name                |
| `steps`      | `250`     | maximum number of step folders kept                                    |
| `versions`   | `10`      | maximum input-variants kept per step                                   |
| `size`       | unset     | maximum total bytes kept; over it, whole entries are evicted by `lru` until under (unset = no size cap) |
| `lru`        | `true`    | evict the least-recently-updated entry when over a limit (`false` = most-recently) |
| `touch`      | `true`    | bump an entry's timestamp on read, so reads keep hot entries alive     |
| `ttl`        | unset     | ISO-8601 duration (e.g. `P30D`); entries not touched within it are evicted on a background sweep (unset = no age eviction) |
| `compressed` | `false`   | store each entry as a single zip file rather than a folder of files    |
| `read`       | `true`    | serve cache reads; `read=false` makes every lookup a miss              |
| `write`      | `true`    | populate the cache; `write=false` serves reads but never writes, evicts, or touches |

Eviction is by file timestamp, performed on write; `touch` keeps recently-read
entries fresh so the count caps (`steps`, `versions`) and the byte cap (`size`)
approximate an LRU. `ttl` adds an age dimension: when set, a store also dispatches a
background sweep to the executor (off the storing thread) that drops every entry whose
last touch is older than the duration, so an idle entry leaves even while the caps have
room. `compressed` trades the hard-linked reads of the folder format
for a packed, transport-friendly layout, approaching the shape a remote cache
server would store. `read` and `write` are the typical CI split: a privileged job
builds with the defaults (both `true`) to populate the cache, while everyone else
sets `write=false` to consume it read-only without mutating it. Setting both to
`false` turns the cache off entirely.

Where this fits
---------------

`BuildExecutorFileCache` (local or a shared folder), `BuildExecutorHttpCache` (a
server), and `BuildExecutorLayeredCache` (one in front of another) are all
implementations of the pluggable `BuildExecutorCache` seam - the same
`fetch`/`store`/`touch` interface. `BuildExecutor.Configuration` resolves the
shared `jenesis.cache.uri` backend; `Project` roots the project-local
`jenesis.project.cache` under the project root and layers it in front. A step
compiled once is reused everywhere its inputs are identical, whether "everywhere"
means the next build on your laptop or every job on the cluster.
