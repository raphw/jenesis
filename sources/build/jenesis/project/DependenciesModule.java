package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.ResolutionListener;
import build.jenesis.Resolver;
import build.jenesis.step.Download;
import build.jenesis.step.Resolve;

public record DependenciesModule(Map<String, Repository> repositories,
                                 Map<String, Resolver> resolvers,
                                 Pinning pinning,
                                 Supplier<ResolutionListener> listener) implements BuildExecutorModule {

    public static final String RESOLVED = "resolved", ARTIFACTS = "artifacts";

    public DependenciesModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, null);
    }

    public DependenciesModule pinning(Pinning pinning) {
        return new DependenciesModule(repositories, resolvers, pinning, listener);
    }

    public DependenciesModule listener(Supplier<ResolutionListener> listener) {
        return new DependenciesModule(repositories, resolvers, pinning, listener);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(RESOLVED,
                new Resolve(repositories, resolvers).pinned(pinning != Pinning.IGNORE).listening(listener),
                inherited.sequencedKeySet());
        buildExecutor.addStep(ARTIFACTS,
                new Download(repositories).pinning(pinning),
                RESOLVED);
    }
}
