module demo.plugin {
    requires build.jenesis;
    requires org.json;
    provides build.jenesis.BuildExecutorModule with demo.plugin.SubstitutionModule;
}
