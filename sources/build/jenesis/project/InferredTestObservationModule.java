package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredTestObservationModule implements BuildExecutorModule {

    public static final String TEST = "test";

    private final Set<Observation> observations;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget;

    public InferredTestObservationModule(Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers,
                                         Pinning pinning,
                                         Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        boolean jacoco = Boolean.getBoolean("jenesis.observe.jacoco");
        this(jacoco ? Set.of(Observation.JACOCO) : Set.of(), repositories, resolvers, pinning, toTarget);
    }

    private InferredTestObservationModule(Set<Observation> observations,
                                          Map<String, Repository> repositories,
                                          Map<String, Resolver> resolvers,
                                          Pinning pinning,
                                          Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        this.observations = observations;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.toTarget = toTarget;
    }

    public InferredTestObservationModule observe(Observation... observations) {
        return observe(Set.of(observations));
    }

    public InferredTestObservationModule observe(Set<Observation> observations) {
        return new InferredTestObservationModule(observations, repositories, resolvers, pinning, toTarget);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        SequencedMap<String, BuildExecutorModule> reports = new LinkedHashMap<>();
        List<ObservabilityEngine> engines = new ArrayList<>();
        if (observations.contains(Observation.JACOCO)) {
            JaCoCo jacoco = new JaCoCo();
            engines.add(jacoco);
            reports.put(jacoco.name(), new JaCoCoModule(repositories, resolvers).pinning(pinning));
        }
        buildExecutor.addModule(TEST, toTarget.apply(engines), inherited.sequencedKeySet());
        SequencedSet<String> reportInputs = new LinkedHashSet<>();
        reportInputs.add(TEST);
        reportInputs.addAll(inherited.sequencedKeySet());
        reports.forEach((name, report) -> buildExecutor.addModule(name, report, reportInputs));
    }
}
