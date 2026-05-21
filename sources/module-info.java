/**
 * Jenesis.
 *
 * A build tool for Java projects, written and configured in Java itself.
 *
 * @release 25
 * @main build.jenesis.Project
 */
module build.jenesis {

    requires jdk.compiler;
    requires java.xml;

    exports build.jenesis;
    exports build.jenesis.docker;
    exports build.jenesis.maven;
    exports build.jenesis.module;
    exports build.jenesis.project;
    exports build.jenesis.step;

    uses build.jenesis.BuildExecutorModule;
}
