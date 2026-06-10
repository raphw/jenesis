package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredTestObservationModule implements BuildExecutorModule {

    public static final String TEST = "test";

    private final Observation observation;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget;

    public InferredTestObservationModule(Observation observation,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers,
                                         Pinning pinning,
                                         Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        this.observation = observation;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.toTarget = toTarget;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        SequencedMap<String, BuildExecutorModule> reports = new LinkedHashMap<>();
        List<ObservabilityEngine> engines = new ArrayList<>();
        if (observation != null) {
            switch (observation) {
                case JACOCO -> {
                    JaCoCo jacoco = new JaCoCo();
                    engines.add(jacoco);
                    reports.put(jacoco.name(), new JaCoCoModule(repositories, resolvers).pinning(pinning));
                }
            }
        }
        buildExecutor.addModule(TEST, toTarget.apply(engines), inherited.sequencedKeySet());
        SequencedSet<String> reportInputs = new LinkedHashSet<>();
        reportInputs.add(TEST);
        reportInputs.addAll(inherited.sequencedKeySet());
        reports.forEach((name, report) -> buildExecutor.addModule(name, report, reportInputs));
    }
}
