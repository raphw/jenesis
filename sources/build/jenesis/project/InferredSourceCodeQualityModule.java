package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredSourceCodeQualityModule implements BuildExecutorModule {

    public static final String CHECKSTYLE = "checkstyle", PMD = "pmd", DETEKT = "detekt",
            KTLINT = "ktlint", SCALASTYLE = "scalastyle", SCALAFMT = "scalafmt", CODENARC = "codenarc";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;

    public InferredSourceCodeQualityModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null);
    }

    private InferredSourceCodeQualityModule(Map<String, Repository> repositories,
                                            Map<String, Resolver> resolvers,
                                            Pinning pinning) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
    }

    public InferredSourceCodeQualityModule pinning(Pinning pinning) {
        return new InferredSourceCodeQualityModule(repositories, resolvers, pinning);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        if (CheckstyleModule.isConfigured(inherited)) {
            buildExecutor.addModule(CHECKSTYLE,
                    new CheckstyleModule(repositories, resolvers).pinning(pinning), inherited.sequencedKeySet());
        }
        if (PmdModule.isConfigured(inherited)) {
            buildExecutor.addModule(PMD,
                    new PmdModule(repositories, resolvers).pinning(pinning), inherited.sequencedKeySet());
        }
        if (DetektModule.isConfigured(inherited)) {
            buildExecutor.addModule(DETEKT,
                    new DetektModule(repositories, resolvers).pinning(pinning), inherited.sequencedKeySet());
        }
        if (KtlintModule.isConfigured(inherited)) {
            buildExecutor.addModule(KTLINT,
                    new KtlintModule(repositories, resolvers).pinning(pinning), inherited.sequencedKeySet());
        }
        if (ScalastyleModule.isConfigured(inherited)) {
            buildExecutor.addModule(SCALASTYLE,
                    new ScalastyleModule(repositories, resolvers).pinning(pinning), inherited.sequencedKeySet());
        }
        if (ScalafmtModule.isConfigured(inherited)) {
            buildExecutor.addModule(SCALAFMT,
                    new ScalafmtModule(repositories, resolvers).pinning(pinning), inherited.sequencedKeySet());
        }
        if (CodeNarcModule.isConfigured(inherited)) {
            buildExecutor.addModule(CODENARC,
                    new CodeNarcModule(repositories, resolvers).pinning(pinning), inherited.sequencedKeySet());
        }
    }
}
