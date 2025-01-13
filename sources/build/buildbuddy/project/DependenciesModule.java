package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.step.Bind;
import build.buildbuddy.step.Checksum;
import build.buildbuddy.step.Download;
import build.buildbuddy.step.Resolve;

import java.nio.file.Path;
import java.util.Map;
import java.util.SequencedMap;

public class DependenciesModule implements BuildExecutorModule {

    public static final String DEPENDENCIES = "dependencies", RESOLVED = "resolved", ARTIFACTS = "artifacts";

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

    public DependenciesModule withChecksums(String algorithm) {
        return new DependenciesModule(repositories, resolvers, algorithm);
    }

    public BuildExecutorModule bound(String file) {
        return (buildExecutor, inherited) -> {
            if (!inherited.containsKey(DEPENDENCIES)) {
                throw new IllegalArgumentException("Expected to receive '" + DEPENDENCIES + "' as input");
            }
            buildExecutor.addStep("bound", Bind.asDependencies(file), DEPENDENCIES);
            doAccept(buildExecutor, "bound");
        };
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        if (!inherited.containsKey(RESOLVED)) {
            throw new IllegalArgumentException("Expected to receive " + RESOLVED + " as input");
        }
        doAccept(buildExecutor, RESOLVED);
    }

    private void doAccept(BuildExecutor buildExecutor, String origin) {
        buildExecutor.addStep("resolved", new Resolve(resolvers), origin);
        if (checksum != null) {
            buildExecutor.addStep("checksum", new Checksum(checksum, repositories), "resolved");
            buildExecutor.addStep(ARTIFACTS, new Download(repositories), "checksum");
        } else {
            buildExecutor.addStep(ARTIFACTS, new Download(repositories), "resolved");
        }
    }
}
