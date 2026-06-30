package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Bind;

public class InferredTestObservationModule implements BuildExecutorModule {

    public static final String TEST = "test", MUTATE = "mutate";

    private final SequencedSet<Path> configuration;
    private final boolean jacoco;
    private final boolean nativeImage;
    private final boolean pitest;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget;

    public InferredTestObservationModule(SequencedSet<Path> configuration,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers,
                                         Pinning pinning,
                                         Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        this(configuration,
                Boolean.parseBoolean(System.getProperty("jenesis.observe.jacoco", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.observe.native", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.mutate.pitest", "true")),
                repositories,
                resolvers,
                pinning,
                toTarget);
    }

    private InferredTestObservationModule(SequencedSet<Path> configuration,
                                          boolean jacoco,
                                          boolean nativeImage,
                                          boolean pitest,
                                          Map<String, Repository> repositories,
                                          Map<String, Resolver> resolvers,
                                          Pinning pinning,
                                          Function<List<ObservabilityEngine>, BuildExecutorModule> toTarget) {
        this.configuration = configuration;
        this.jacoco = jacoco;
        this.nativeImage = nativeImage;
        this.pitest = pitest;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.toTarget = toTarget;
    }

    public InferredTestObservationModule jacoco(boolean jacoco) {
        return new InferredTestObservationModule(configuration, jacoco, nativeImage, pitest, repositories, resolvers, pinning, toTarget);
    }

    public InferredTestObservationModule nativeImage(boolean nativeImage) {
        return new InferredTestObservationModule(configuration, jacoco, nativeImage, pitest, repositories, resolvers, pinning, toTarget);
    }

    public InferredTestObservationModule pitest(boolean pitest) {
        return new InferredTestObservationModule(configuration, jacoco, nativeImage, pitest, repositories, resolvers, pinning, toTarget);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        SequencedMap<String, BuildExecutorModule> reports = new LinkedHashMap<>();
        List<ObservabilityEngine> engines = new ArrayList<>();
        if (jacoco && BuildStep.locate(configuration, "jacoco.properties") != null) {
            JaCoCo engine = new JaCoCo();
            engines.add(engine);
            reports.put(engine.name(), new JaCoCoModule(repositories, resolvers).pinning(pinning));
        }
        if (nativeImage && BuildStep.locate(configuration, "graal.properties") != null) {
            NativeImageAgent engine = new NativeImageAgent();
            engines.add(engine);
            reports.put(engine.name(), new NativeImageAgentModule());
        }
        buildExecutor.addModule(TEST, toTarget.apply(engines), inherited.sequencedKeySet());
        SequencedSet<String> reportInputs = new LinkedHashSet<>();
        reportInputs.add(TEST);
        reportInputs.addAll(inherited.sequencedKeySet());
        reports.forEach((name, report) -> buildExecutor.addModule(name, report, reportInputs));
        Bind.configuredByProperties(buildExecutor, inherited.sequencedKeySet(), MUTATE, pitest,
                BuildStep.locate(configuration, "pitest.properties"),
                properties -> new PiTestModule(repositories, resolvers).pinning(pinning).config(properties));
    }
}
