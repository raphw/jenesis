Docker isolation demo
=====================

Build and run a Java project inside a throwaway Docker container so that untrusted
code - the tests run during the build, the dependencies they drag in, and the
artifact's own `main` - cannot reach your host secrets or write outside the sandbox.
This demo first shows the leak on the host (a single-class project lifts a
credentials file and an environment secret at build time and again at run time),
then confines both the build and the launched program with Docker.

Set up the secrets
------------------

    printf 'aws_secret_key=PSEUDO-FILE-SECRET\n' > ~/.demo-credentials
    export DEMO_SECRET=PSEUDO-ENV-SECRET

(Use a throwaway file - the actors overwrite it. Remove it and unset the variable
when you are done.)

On the host (the leak)
----------------------

Build the project. The build compiles and runs the test, which reads both secrets
and overwrites the file:

    java build/jenesis/Project.java
    cat ~/.demo-credentials          # now reads: overwritten by the test

JUnit captures the test's console output, so the proof that the test ran is the
clobbered file. Now run the program with `Execute`, whose `main` prints what it
extracted:

    printf 'aws_secret_key=PSEUDO-FILE-SECRET\n' > ~/.demo-credentials   # restore it
    java build/jenesis/Execute.java

    [program main] env DEMO_SECRET = PSEUDO-ENV-SECRET
    [program main] extracted /home/you/.demo-credentials: aws_secret_key=PSEUDO-FILE-SECRET
    [program main] overwrote /home/you/.demo-credentials

A single-class project read and clobbered a credentials file and lifted an
environment secret, at build time and again at run time, with no custom build
code involved.

What the leak stands for
------------------------

A build runs untrusted code even when nothing about it is customised. The stock
pipeline compiles and runs your **tests** (and everything your test dependencies
drag in), and the artifact it produces has a **`main`** that runs later - all with
the full rights of whoever started the build. This is a plain `Project`-based
build on purpose: customising the build would only make it worse, since a custom
launcher's own `main` is one more place a vulnerability can hide. The point is
that you do not have to customise anything to be exposed.

Two actors here reach for host secrets - a **test** that runs during the build,
and the built program's **`main`** - and each goes after two things:

- a secret **file** at `~/.demo-credentials` (standing in for `~/.aws/credentials`,
  `~/.ssh/id_rsa`, and the like);
- a secret **environment variable** `DEMO_SECRET` (standing in for a CI secret).

There are no assertions. Each actor prints the environment variable, prints the
file's contents, and overwrites the file, so you can watch the leak and the
tampering directly.

The test and the `main` here are the project's own code, but they stand in for
the more realistic threat: the **external dependencies** you did not write. A
build resolves third-party artifacts and then runs their code - a test engine and
its transitive closure during the build, the runtime closure when the program
runs - so a single compromised or hijacked dependency (a malicious release, a
taken-over account, a typo-squatted coordinate) executes with the same host rights
as the actors above. Pinning helps with a *different* half of this: Jenesis pins
every dependency to a version **and** an `SHA-256` checksum (see `../demo-02-java-modular`),
so you always get the exact bytes you vetted rather than a silently swapped
artifact. But pinning cannot tell whether those vetted bytes were malicious to
begin with - it guarantees *what* runs, not that what runs is safe. Docker
addresses the other half: it confines what that code can reach when it executes,
so even a malicious dependency cannot read the host secrets or write outside the
sandbox.

Layout
------

Its only build descriptors are `module-info.java` files (no `pom.xml`), so Jenesis
auto-detects the MODULAR_TO_MAVEN layout. The test lives in its own `@jenesis.test`
module, the standard modular way to ship tests:

    demo/demo-30-docker-isolation
    |-- build/jenesis                    symlink to ../../../sources/build/jenesis
    |-- app/
    |   |-- module-info.java             module demo.dockerisolation, @jenesis.main sample.Sample
    |   `-- sample/Sample.java           main; reads the env var and the file, overwrites it (Sample.peek)
    `-- app-test/
        |-- module-info.java             @jenesis.test demo.dockerisolation; pins the JUnit closure
        `-- sampletest/SampleTest.java   a test that calls Sample.peek during the build

Confining it with Docker
------------------------

Jenesis can run the build and the launched program inside a throwaway container,
where neither the home directory nor the host environment is present.

**The program.** `Execute` launches `main` in a container with
`jenesis.execute.docker`:

    java -Djenesis.execute.docker=true build/jenesis/Execute.java

    [program main] env DEMO_SECRET = <unset - out of reach>
    [program main] cannot read /.demo-credentials - out of reach

The container does not get `DEMO_SECRET` (the environment is not forwarded) and
its home directory is not the host's (so `~/.demo-credentials` resolves to a path
that is not mounted). The artifact runs, but the secrets are out of reach. Note
this sandboxes only the program: the build still ran on the host, so the test
still leaked. To sandbox the build too, run it in a container as well - but with
one extra flag in this repository, explained next:

    java -Djenesis.project.docker=true \
         -Djenesis.project.docker.mount=../../sources \
         build/jenesis/Project.java

`jenesis.project.docker` runs the whole build - the test included - inside the
container, so the build-time actor is confined the same way: the test prints
`DEMO_SECRET is unset` / `out of reach` and the host secret file is left
untouched.

Why the extra mount is needed here. The demos share one engine through the
`build/jenesis -> ../../../sources/build/jenesis` symlink, which points outside
the project root. `jenesis.project.docker` mounts only the project root, so inside
the container that symlink dangles and the build cannot even start
(`ClassNotFoundException: build.jenesis.Project.java`). `jenesis.project.docker.mount`
adds bind mounts to the container; pointing it at the shared `sources/` tree
(`../../sources`, resolved against the project root) makes the symlink target
present at the same path inside the container, so the engine resolves. The mount
is **read-only**, so the sandboxed build can read the engine sources but cannot
write back to the host - the isolation the demo is about is preserved. The path is
given as a bare `host` (mounted at the same path, `host:host`) precisely because
the symlink resolves to a host absolute path that must match inside the container;
`host:container` would remap it and the symlink would not resolve.

A normally vendored project (a real `build/jenesis/` directory inside the root,
from the curl or SDKMAN install) has the engine under the mounted root and needs
no extra mount. `jenesis.execute.docker` is unaffected here either way, since the
build runs on the host and only the finished artifact is launched in the
container. The same `jenesis.execute.docker.mount` flag exists for symmetry when a
launched program needs a host path made visible.

When a sandboxed build or program legitimately needs to *write* to a host path -
say a generated-output directory you want to keep after the container exits -
`jenesis.project.docker.mountWritable` (and its `jenesis.execute.docker.mountWritable`
twin) add **read-write** bind mounts, the counterpart to the read-only `.mount`
above. Reach for it sparingly: every writable mount is a hole in the very
confinement this demo is about, so widen the sandbox only for the exact path that
needs it.

Read-only repositories: `export` does not work in Docker
--------------------------------------------------------

Docker mounts the local Maven and Jenesis repositories (`~/.m2`, `~/.jenesis`)
**read-only**. So dependencies must already be cached (the container can read the
repository but not populate it - warm the cache with a host build first), and
`export` fails: publishing writes into those repositories, so

    java -Djenesis.project.docker=true build/jenesis/Project.java export

aborts with an `AccessDeniedException` from `MavenRepositoryExport`. Stage inside
the container if you want (`stage` only writes under `target/`), but run `export`
on the host.

This is a local exercise: it needs a Docker daemon, so unlike the other demos it
is not part of CI.

The build is code too, and the chicken-and-egg problem
------------------------------------------------------

Docker confines what the build *runs*, but there is a deeper trap with builds
whose logic ships as code. A Jenesis build is launched with
`java build/jenesis/Project.java`, and a customised one with `java build/Demo.java`
- in both cases you execute the project's own `build/` sources to build it. That
is the chicken and egg: to find out what the build does you have to run it, and
running it is exactly the thing you wanted to vet first. Reading the sources by
hand does not scale, and a `build/Demo.java` (or a tampered `build/jenesis/`) gets
host rights the moment you launch it - before any dependency, test, or artifact
`main` is reached. So a custom build cannot vet itself.

What breaks the cycle is that a standard Jenesis project carries **no build logic
to execute at all**. The build is described declaratively - a `pom.xml` or
`module-info.java` giving the project structure and its dependency coordinates -
and nothing in that description runs code. So an *untrusted* project can be built
by a *trusted, external* Jenesis that you already have. Installed through SDKMAN,
`jenesis` runs the SDK's own copy of `Project.main(...)` against the current
directory and never touches the project's `build/` sources:

    sdk install jenesis
    jenesis                 # builds the project in . with the SDK's trusted engine

Now the only untrusted code left is the dependencies, the tests, and the
artifact's `main` - the very things the Docker flags above confine - while the
build engine itself is the one you trust. (Running `jenesis` directly limits you to
system-property configuration; that is the price of not executing the project's
build code.)

And if a project does vendor `build/jenesis/`, you do not have to trust it on
faith. `jenesis-validate` extracts the SDK's bundled engine sources and
SHA-256-compares them file by file against the project's linked `build/jenesis`,
reporting any `differs` / `missing` / `additional` file and whether the recorded
version matches:

    jenesis-validate        # confirm the linked engine is the unmodified SDK version

A clean report means the embedded engine is byte-for-byte the trusted one, so
`java build/jenesis/Project.java` runs exactly the code SDKMAN shipped - not a
tampered fork.

Shipping the app as a container image with `bundle`
---------------------------------------------------

The flags above run the *build* in Docker; the inverse is shipping the *built
application* as a container image, and `bundle` makes that a short Dockerfile
without `jpackage`, `jlink`, or `native-image`. Build a portable bundle:

    java -Djenesis.java.bundle=true build/jenesis/Project.java

That stages a `bundle.zip` under `target/` carrying everything the app needs to
launch, with no assumptions about the host:

    application.properties        mainModule=demo.dockerisolation, mainClass=sample.Sample
    modulepath/*.jar              the modular jar (this module)
    classpath/*.jar               any plain-classpath jars

Because the bundle is self-describing - `application.properties` names the entry
point and the two folders split the run path - any JVM base image can run it.
Unzip it into an `eclipse-temurin` (or any JDK/JRE) image and launch the command
those properties spell out:

    FROM eclipse-temurin:25-jre
    COPY modulepath/ /app/modulepath/
    WORKDIR /app
    ENTRYPOINT ["java", "--module-path", "modulepath", \
                "--module", "demo.dockerisolation/sample.Sample"]

A classpath-only app swaps the `--module-path`/`--module` pair for
`-cp 'classpath/*' <mainClass>`. The same image definition works under Podman.
Unlike `jpackage`/`jlink`, the bundle embeds no runtime, so the JVM comes from the
base image and a single image can be rebased onto a newer JVM without rebuilding
the app.
