module demo.external.plugin {
    requires build.jenesis;
    provides build.jenesis.BuildExecutorModule with demo.plugin.StampModule;
}
