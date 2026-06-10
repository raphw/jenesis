/**
 * The Scala counterpart to the Java code-quality demo. A
 * {@code scalastyle-config.xml} activates Scalastyle and a {@code .scalafmt.conf}
 * activates scalafmt; both inspect the Scala sources, and the same
 * {@code .scalafmt.conf} drives scalafmt as a formatter in verify mode. The
 * Scala compiler is pinned in its own {@code scalac} group while the quality
 * tools float their own {@code RELEASE}.
 *
 * @jenesis.release 25
 * @jenesis.pin org.scala-lang/scala-library 2.13.18 SHA-256/4e85d96ff7bc7dc627985523c3541b9917aaa08e956391380c42db21a2c4e5a0
 * @jenesis.pin scala.library 2.13.18
 * @jenesis.pin scalac/maven/org.scala-lang.modules/scala-asm 9.9.0-scala-1 SHA-256/75ac366e8ecb691e06a7e85041eed0f67919a646e5262fa0901225698c104375
 * @jenesis.pin scalac/maven/org.scala-lang/scala-library 3.8.4-RC3 SHA-256/f2052e9a932973699c9ef575da2ff780245a3f464dbfa28871a44fd27028fc35
 * @jenesis.pin scalac/maven/org.scala-lang/scala3-compiler_3 3.8.4-RC3 SHA-256/8c1f104682d0bda19eecca2ab0e6e90aca0c49d934fb69990fbc8a6f3799cbd9
 * @jenesis.pin scalac/maven/org.scala-lang/scala3-interfaces 3.8.4-RC3 SHA-256/3fcde09e1aad15e4a308882220dd6008db9e51223775625333201048f334098e
 * @jenesis.pin scalac/maven/org.scala-lang/scala3-library_3 3.8.4-RC3 SHA-256/a56d1dd4134af60db7d41a3005fb5e5ed59d77f8c381bb675d55ba2c60208f3d
 * @jenesis.pin scalac/maven/org.scala-lang/tasty-core_3 3.8.4-RC3 SHA-256/5fca75da0a575775dd468923fed00d8efb4f0c86ed05f7c86dc44cdf3aec6fc1
 * @jenesis.pin scalac/maven/org.scala-sbt/compiler-interface 1.10.7 SHA-256/2bacc5761e03920a228e5c9d20b33d9c51d43aaf2f52e8f839ece630966eb880
 * @jenesis.pin scalac/maven/org.scala-sbt/util-interface 1.10.7 SHA-256/1d6b91efa42b70fc064caed6d62962374e13b27737f885a87c84c667b30be625
 */
module sample.scala {
    requires scala.library;

    exports sample;
}
