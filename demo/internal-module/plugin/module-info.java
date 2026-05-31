module demo.plugin {
    requires build.jenesis;
    provides build.jenesis.BuildExecutorModule with demo.plugin.GreetingModule;
}
