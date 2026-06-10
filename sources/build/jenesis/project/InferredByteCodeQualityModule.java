package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredByteCodeQualityModule implements BuildExecutorModule {

    public static final String SPOTBUGS = "spotbugs";

    private final Path configuration;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;

    public InferredByteCodeQualityModule(Path configuration,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers) {
        this(configuration, repositories, resolvers, null);
    }

    private InferredByteCodeQualityModule(Path configuration,
                                          Map<String, Repository> repositories,
                                          Map<String, Resolver> resolvers,
                                          Pinning pinning) {
        this.configuration = configuration;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
    }

    public InferredByteCodeQualityModule pinning(Pinning pinning) {
        return new InferredByteCodeQualityModule(configuration, repositories, resolvers, pinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        InferredSourceCodeQualityModule.wire(buildExecutor, inherited, SPOTBUGS,
                SpotBugsModule.configurationFile(configuration),
                new SpotBugsModule(repositories, resolvers).pinning(pinning));
    }
}
