package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Bind;

public class InferredSourceCodeQualityModule implements BuildExecutorModule {

    public static final String CHECKSTYLE = "checkstyle",
            PMD = "pmd",
            DETEKT = "detekt",
            KTLINT = "ktlint",
            SCALASTYLE = "scalastyle",
            SCALAFMT = "scalafmt",
            CODENARC = "codenarc";

    private final Path configuration;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;

    public InferredSourceCodeQualityModule(Path configuration,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers) {
        this(configuration, repositories, resolvers, null);
    }

    private InferredSourceCodeQualityModule(Path configuration,
                                            Map<String, Repository> repositories,
                                            Map<String, Resolver> resolvers,
                                            Pinning pinning) {
        this.configuration = configuration;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
    }

    public InferredSourceCodeQualityModule pinning(Pinning pinning) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        wire(buildExecutor, inherited, CHECKSTYLE, CheckstyleModule.configurationFile(configuration),
                new CheckstyleModule(repositories, resolvers).pinning(pinning));
        wire(buildExecutor, inherited, PMD, PmdModule.configurationFile(configuration),
                new PmdModule(repositories, resolvers).pinning(pinning));
        wire(buildExecutor, inherited, DETEKT, DetektModule.configurationFile(configuration),
                new DetektModule(repositories, resolvers).pinning(pinning));
        wire(buildExecutor, inherited, KTLINT, KtlintModule.configurationFile(configuration),
                new KtlintModule(repositories, resolvers).pinning(pinning));
        wire(buildExecutor, inherited, SCALASTYLE, ScalastyleModule.configurationFile(configuration),
                new ScalastyleModule(repositories, resolvers).pinning(pinning));
        wire(buildExecutor, inherited, SCALAFMT, ScalafmtModule.configurationFile(configuration),
                new ScalafmtModule(repositories, resolvers).pinning(pinning));
        wire(buildExecutor, inherited, CODENARC, CodeNarcModule.configurationFile(configuration),
                new CodeNarcModule(repositories, resolvers).pinning(pinning));
    }

    static void wire(BuildExecutor buildExecutor,
                     SequencedMap<String, Path> inherited,
                     String name,
                     Path configurationFile,
                     BuildExecutorModule module) {
        if (configurationFile == null) {
            return;
        }
        String configuration = name + "-configuration";
        buildExecutor.addSource(configuration,
                new Bind(Map.of(Path.of(""), configurationFile.getFileName())),
                configurationFile);
        SequencedSet<String> inputs = new LinkedHashSet<>();
        inputs.add(configuration);
        inputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addModule(name, module, inputs);
    }
}
