package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.step.Checksum;
import build.buildbuddy.step.Download;
import build.buildbuddy.step.Resolve;

import java.nio.file.Path;
import java.util.Map;
import java.util.SequencedMap;

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
            buildExecutor.addStep(PREPARED, new Resolve(resolvers), inherited.sequencedKeySet());
            buildExecutor.addStep(RESOLVED, new Checksum(checksum, repositories), PREPARED);
        } else {
            buildExecutor.addStep(RESOLVED, new Resolve(resolvers), inherited.sequencedKeySet());
        }
        buildExecutor.addStep(ARTIFACTS, new Download(repositories), RESOLVED);
    }
}
