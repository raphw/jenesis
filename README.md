Build Buddy
===========

POC for a simple-enough, yet powerful enough build tool that targets Java, and is written and configured in Java, and 
that has inherent support for (a) parallel incremental builds, and therewith build immutability and (b) seamless 
validation of downloads from external (untrusted) sources by checksum validation.

As a goal, the build tool should be stored in source alongside a project without any external installation. A build 
should be executable by using Java alone, by embracing the JVMs ability to run programs from source files. This avoids
storing precompiled binaries in repositories, and allows for the execution of builds in environments that only have the
JVM installed without the deployment of build tool wrappers that often entail a (cachable) download of the tool. It
should be possible to manage updates of these sources easily, and to add extensions (plugins) to the base implementation
alongside.

The build tool should only rely on the Java standard library and should be launchable using a command such as:

    java build.Main

where Main is a class located in the project's build folder. This should be employed for this project, too, so it can
document the use of this tool within its own source.

The POC is currently missing:
- Task for JUnit (4 first, keep it simple, with feedback).
- Refactor this project to use itself as build tool. (Retain POM for IDE support.)
- Some form of qualifier information for results (see need in Java step).
- Mechanism for logging and "non-build output", structured result in executor.
- Builder class for a BuildExecutor that offers convenient defaults.
- Task to create a dependencies file from a POM file.
- Task to create a POM file from dependencies.
- Task for javadoc tool.
- Task for source jars.
- Task to add GPG signatures of artifacts.
- Task to publish to Maven Central.
- Extend all build step implementations to support their standard options.
