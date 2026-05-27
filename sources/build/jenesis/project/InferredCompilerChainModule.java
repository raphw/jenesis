package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Javac;

public class InferredCompilerChainModule implements BuildExecutorModule {

    public static final String JAVA = "java", KOTLIN = "kotlin", SCALA = "scala";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean strictPinning;
    private final BuildStep javac;

    public InferredCompilerChainModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, Javac.tool(), false);
    }

    public InferredCompilerChainModule(Map<String, Repository> repositories,
                                       Map<String, Resolver> resolvers,
                                       BuildStep javac,
                                       boolean strictPinning) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.javac = javac;
        this.strictPinning = strictPinning;
    }

    public InferredCompilerChainModule strictPinning(boolean strictPinning) {
        return new InferredCompilerChainModule(repositories, resolvers, javac, strictPinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(JAVA, javac, upstream);
        SequencedSet<String> kotlinInputs = new LinkedHashSet<>();
        kotlinInputs.add(JAVA);
        kotlinInputs.addAll(upstream);
        buildExecutor.addModule(KOTLIN,
                new KotlinCompilerModule(repositories, resolvers).strictPinning(strictPinning),
                kotlinInputs);
        SequencedSet<String> scalaInputs = new LinkedHashSet<>();
        scalaInputs.add(KOTLIN);
        scalaInputs.addAll(kotlinInputs);
        buildExecutor.addModule(SCALA,
                new ScalaCompilerModule(repositories, resolvers).strictPinning(strictPinning),
                scalaInputs);
    }
}
