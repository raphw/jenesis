package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Pinning;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Bind;

public class InferredSourceCodeQualityModule implements BuildExecutorModule {

    public static final String CHECKSTYLE = "checkstyle",
            PMD = "pmd",
            DETEKT = "detekt",
            KTLINT = "ktlint",
            SCALASTYLE = "scalastyle",
            SCALAFMT = "scalafmt",
            CODENARC = "codenarc";

    private final Path configuration;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final boolean checkstyle;
    private final boolean pmd;
    private final boolean detekt;
    private final boolean ktlint;
    private final boolean scalastyle;
    private final boolean scalafmt;
    private final boolean codenarc;

    public InferredSourceCodeQualityModule(Path configuration,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers) {
        this(configuration, repositories, resolvers, null,
                Boolean.parseBoolean(System.getProperty("jenesis.source.checkstyle", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.source.pmd", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.source.detekt", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.source.ktlint", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.source.scalastyle", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.source.scalafmt", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.source.codenarc", "true")));
    }

    private InferredSourceCodeQualityModule(Path configuration,
                                            Map<String, Repository> repositories,
                                            Map<String, Resolver> resolvers,
                                            Pinning pinning,
                                            boolean checkstyle,
                                            boolean pmd,
                                            boolean detekt,
                                            boolean ktlint,
                                            boolean scalastyle,
                                            boolean scalafmt,
                                            boolean codenarc) {
        this.configuration = configuration;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.checkstyle = checkstyle;
        this.pmd = pmd;
        this.detekt = detekt;
        this.ktlint = ktlint;
        this.scalastyle = scalastyle;
        this.scalafmt = scalafmt;
        this.codenarc = codenarc;
    }

    public InferredSourceCodeQualityModule pinning(Pinning pinning) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    public InferredSourceCodeQualityModule checkstyle(boolean checkstyle) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    public InferredSourceCodeQualityModule pmd(boolean pmd) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    public InferredSourceCodeQualityModule detekt(boolean detekt) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    public InferredSourceCodeQualityModule ktlint(boolean ktlint) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    public InferredSourceCodeQualityModule scalastyle(boolean scalastyle) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    public InferredSourceCodeQualityModule scalafmt(boolean scalafmt) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    public InferredSourceCodeQualityModule codenarc(boolean codenarc) {
        return new InferredSourceCodeQualityModule(configuration, repositories, resolvers, pinning,
                checkstyle, pmd, detekt, ktlint, scalastyle, scalafmt, codenarc);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), CHECKSTYLE, checkstyle,
                CheckstyleModule.configurationFile(configuration),
                new CheckstyleModule(repositories, resolvers).pinning(pinning));
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), PMD, pmd,
                PmdModule.configurationFile(configuration),
                new PmdModule(repositories, resolvers).pinning(pinning));
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), DETEKT, detekt,
                DetektModule.configurationFile(configuration),
                new DetektModule(repositories, resolvers).pinning(pinning));
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), KTLINT, ktlint,
                KtlintModule.configurationFile(configuration),
                new KtlintModule(repositories, resolvers).pinning(pinning));
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), SCALASTYLE, scalastyle,
                ScalastyleModule.configurationFile(configuration),
                new ScalastyleModule(repositories, resolvers).pinning(pinning));
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), SCALAFMT, scalafmt,
                ScalafmtModule.configurationFile(configuration),
                new ScalafmtModule(repositories, resolvers).pinning(pinning));
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), CODENARC, codenarc,
                CodeNarcModule.configurationFile(configuration),
                new CodeNarcModule(repositories, resolvers).pinning(pinning));
    }
}
