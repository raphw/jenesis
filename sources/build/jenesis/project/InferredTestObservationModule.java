package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredTestObservationModule implements BuildExecutorModule {

    public static final String TEST = "test";

    private final boolean jacoco;
    private final boolean nativeImage;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget;

    public InferredTestObservationModule(Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers,
                                         Pinning pinning,
                                         Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        this(Boolean.getBoolean("jenesis.observe.jacoco"),
                Boolean.getBoolean("jenesis.observe.native"),
                repositories,
                resolvers,
                pinning,
                toTarget);
    }

    private InferredTestObservationModule(boolean jacoco,
                                          boolean nativeImage,
                                          Map<String, Repository> repositories,
                                          Map<String, Resolver> resolvers,
                                          Pinning pinning,
                                          Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        this.jacoco = jacoco;
        this.nativeImage = nativeImage;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.toTarget = toTarget;
    }

    public InferredTestObservationModule jacoco(boolean jacoco) {
        return new InferredTestObservationModule(jacoco, nativeImage, repositories, resolvers, pinning, toTarget);
    }

    public InferredTestObservationModule nativeImage(boolean nativeImage) {
        return new InferredTestObservationModule(jacoco, nativeImage, repositories, resolvers, pinning, toTarget);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        SequencedMap<String, BuildExecutorModule> reports = new LinkedHashMap<>();
        List<ObservabilityEngine> engines = new ArrayList<>();
        if (jacoco) {
            JaCoCo engine = new JaCoCo();
            engines.add(engine);
            reports.put(engine.name(), new JaCoCoModule(repositories, resolvers).pinning(pinning));
        }
        if (nativeImage) {
            NativeImageAgent engine = new NativeImageAgent();
            engines.add(engine);
            reports.put(engine.name(), new NativeImageAgentModule());
        }
        buildExecutor.addModule(TEST, toTarget.apply(engines), inherited.sequencedKeySet());
        SequencedSet<String> reportInputs = new LinkedHashSet<>();
        reportInputs.add(TEST);
        reportInputs.addAll(inherited.sequencedKeySet());
        reports.forEach((name, report) -> buildExecutor.addModule(name, report, reportInputs));
    }
}
