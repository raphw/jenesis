module buildbuddy {
    requires java.xml;
    requires jdk.compiler;
    exports build.buildbuddy;
    exports build.buildbuddy.maven;
    exports build.buildbuddy.step;
}