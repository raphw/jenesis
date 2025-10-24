package build.jenesis.project;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Checksum;
import build.jenesis.step.Download;
import build.jenesis.step.Resolve;

import module java.base;

public class DependenciesModule implements BuildExecutorModule {

    public static final String RESOLVED = "resolved", ARTIFACTS = "artifacts", PREPARED = "prepared";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final String checksum;

    public DependenciesModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null);
    }

    private DependenciesModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers, String checksum) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.checksum = checksum;
    }

    public DependenciesModule computeChecksums(String algorithm) {
        return new DependenciesModule(repositories, resolvers, algorithm);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        if (checksum != null) {
            buildExecutor.addStep(PREPARED, new Resolve(repositories, resolvers), inherited.sequencedKeySet());
            buildExecutor.addStep(RESOLVED, new Checksum(checksum, repositories), PREPARED);
        } else {
            buildExecutor.addStep(RESOLVED, new Resolve(repositories, resolvers), inherited.sequencedKeySet());
        }
        buildExecutor.addStep(ARTIFACTS, new Download(repositories), RESOLVED);
    }
}
