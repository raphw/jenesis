module build.jenesis {

    requires jdk.compiler;
    requires java.xml;

    exports build.jenesis;
    exports build.jenesis.maven;
    exports build.jenesis.module;
    exports build.jenesis.project;
    exports build.jenesis.step;
}
