module buildbuddy {

    requires jdk.compiler;
    requires java.desktop;
    requires org.graalvm.nativeimage;

    exports build.buildbuddy;
    exports build.buildbuddy.maven;
    exports build.buildbuddy.module;
    exports build.buildbuddy.project;
    exports build.buildbuddy.step;
}