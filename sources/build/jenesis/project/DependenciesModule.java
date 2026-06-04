package build.jenesis.project;

import module java.base;
import build.jenesis.DependencyScope;
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
                                 DependencyScope scope,
                                 Pinning pinning,
                                 String tag,
                                 Supplier<ResolutionListener> listener) implements BuildExecutorModule {

    public static final String RESOLVED = "resolved", ARTIFACTS = "artifacts";

    public DependenciesModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers, DependencyScope scope) {
        this(repositories, resolvers, scope, null, null, null);
    }

    public DependenciesModule pinning(Pinning pinning) {
        return new DependenciesModule(repositories, resolvers, scope, pinning, tag, listener);
    }

    public DependenciesModule tag(String tag) {
        return new DependenciesModule(repositories, resolvers, scope, pinning, tag, listener);
    }

    public DependenciesModule listener(Supplier<ResolutionListener> listener) {
        return new DependenciesModule(repositories, resolvers, scope, pinning, tag, listener);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(RESOLVED,
                new Resolve(repositories, resolvers, scope).pinned(pinning != Pinning.IGNORE).listening(listener),
                inherited.sequencedKeySet());
        buildExecutor.addStep(ARTIFACTS,
                new Download(repositories).pinning(pinning).tag(tag),
                RESOLVED);
    }
}
