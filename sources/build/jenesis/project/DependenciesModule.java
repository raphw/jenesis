package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Download;
import build.jenesis.step.Resolve;

public record DependenciesModule(Map<String, Repository> repositories,
                                 Map<String, Resolver> resolvers,
                                 boolean compile,
                                 Pinning pinning,
                                 String scope) implements BuildExecutorModule {

    public static final String RESOLVED = "resolved", ARTIFACTS = "artifacts";

    public DependenciesModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers, boolean compile) {
        this(repositories, resolvers, compile, null, null);
    }

    public DependenciesModule(Map<String, Repository> repositories,
                              Map<String, Resolver> resolvers,
                              boolean compile,
                              Pinning pinning) {
        this(repositories, resolvers, compile, pinning, null);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(RESOLVED,
                new Resolve(repositories, resolvers, compile).pinned(pinning != Pinning.IGNORE),
                inherited.sequencedKeySet());
        buildExecutor.addStep(ARTIFACTS,
                new Download(repositories, pinning, scope),
                RESOLVED);
    }
}
