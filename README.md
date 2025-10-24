Jenesis
=======

POC for a simple-enough, yet powerful enough build tool that targets Java, and is written and configured in Java, and 
that has inherent support for (a) parallel incremental builds, and therewith build reproducibility and (b) supply-chain 
security when it comes to downloading external resources.

As a side goal, the build tool should be storable as source code alongside another project, without a need of explicit 
installation. At the same time, it should be possible to compile the build to avoid repeated compilation. Doing so, a 
build should be executable by using the JVM only once a copy of a project's source is obtained, by embracing the JVM's 
ability to run programs from source files. This avoids storing precompiled binaries in repositories, and allows for the 
execution of builds in environments that only have the JVM installed without the deployment of build tool wrappers that 
often entail a (cachable) download of the tool. It should be possible to manage updates of these sources easily, and to 
add extensions (plugins) to the base implementation alongside.

The build tool should only rely on the Java standard library and should be launchable using a command such as:

    java build/Main.java

where `Main` is a user defined class located in the project's build folder, which assembles the build using the
classes of this build tool. This is also demonstrated within this project, where the build tool is the source but
also linked into the build folder as it would be suggested to users of this tool. This would also be possible by
using for example Git Submodules. For IDE-support, a POM is stored alongside, and it should always be possible to
build this project using Maven to debug errors in the project source which is used for building itself.

By automatically caching results of single build steps, expensive but commonly stable tasks should be cached implicitly.
This avoids the need of, for example, repeated resolution of dependency trees. As the result of such resolution can
be stored in a textual format, dependency resolution could also be checked into a source repository. This allows both 
to store checksums of previously resolved files for validation, and stabilizes resolution process which can otherwise
render builds non-deterministic, for example when version ranges are declared in (transitive) dependencies.

To allow for an effective implementation of such caching, dependency descriptors should not be defined as a part of the 
build description, but separately. In the simplest format, it should always be possible to express information in the 
Java properties format. Based on this, it is trivial to translate common descriptions into this format. As a 
demonstration of this concept, Java module info classes should be offered as a canonical way of defining (build) module 
names and dependencies.

Specific implementations of dependency resolution or repositories should not be hard-coded into the build tool. 
There should, for example, not be any hard dependency on Maven concepts, to allow for their substitution. 

The POC is currently missing:
- Add module for test discovery to include needed runner dependency.
- Convention object for `MultiProject` to avoid manual construction of identifiers.
- Task for creating POM files from module-info.java.
- Task for javadoc.
- Task for source-jars.
- Task to add GPG signatures of artifacts.
- Task to publish to Maven Central and local Maven repository.
- Extend all build step implementations to support their standard options.
