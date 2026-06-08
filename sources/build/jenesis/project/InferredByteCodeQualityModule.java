package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredByteCodeQualityModule implements BuildExecutorModule {

    public static final String SPOTBUGS = "spotbugs";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;

    public InferredByteCodeQualityModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null);
    }

    private InferredByteCodeQualityModule(Map<String, Repository> repositories,
                                          Map<String, Resolver> resolvers,
                                          Pinning pinning) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
    }

    public InferredByteCodeQualityModule pinning(Pinning pinning) {
        return new InferredByteCodeQualityModule(repositories, resolvers, pinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        if (hasFile(inherited, "spotbugs-exclude.xml") || hasFile(inherited, "spotbugs.xml")) {
            buildExecutor.addModule(SPOTBUGS,
                    new SpotBugsModule(repositories, resolvers).pinning(pinning), upstream);
        }
    }

    private static boolean hasFile(SequencedMap<String, Path> inherited, String fileName) {
        for (Path folder : inherited.values()) {
            if (Files.isRegularFile(folder.resolve(fileName))) {
                return true;
            }
        }
        return false;
    }
}
