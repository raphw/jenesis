package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredTestObservationModule implements BuildExecutorModule {

    public static final String TEST = "test", JACOCO = "jacoco";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget;

    public InferredTestObservationModule(Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers,
                                         Pinning pinning,
                                         Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.toTarget = toTarget;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        SequencedMap<String, BuildExecutorModule> reports = new LinkedHashMap<>();
        List<ObservabilityEngine> engines = new ArrayList<>();
        if (Boolean.getBoolean("jenesis.test.coverage")) {
            engines.add(new JaCoCo());
            reports.put(JACOCO, new JaCoCoModule(repositories, resolvers).pinning(pinning));
        }
        buildExecutor.addModule(TEST, toTarget.apply(engines), inherited.sequencedKeySet());
        SequencedSet<String> reportInputs = new LinkedHashSet<>();
        reportInputs.add(TEST);
        reportInputs.addAll(inherited.sequencedKeySet());
        reports.forEach((name, report) -> buildExecutor.addModule(name, report, reportInputs));
    }
}
