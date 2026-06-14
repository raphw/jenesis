package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Bind;

public class InferredSourceFormattingModule implements BuildExecutorModule {

    public enum JavaFormatter {
        GOOGLE, PALANTIR
    }

    public static final String GOOGLE_JAVA_FORMAT = "google-java-format",
            PALANTIR_JAVA_FORMAT = "palantir-java-format",
            KTLINT = "ktlint-format",
            SCALAFMT = "scalafmt-format";

    private final Path configuration;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final JavaFormatter javaFormatter;
    private final boolean verify;
    private final boolean ktlint;
    private final boolean scalafmt;

    public InferredSourceFormattingModule(Path configuration,
                                          Map<String, Repository> repositories,
                                          Map<String, Resolver> resolvers) {
        this(configuration, repositories, resolvers, null,
                switch (System.getProperty("jenesis.format.java", "")) {
                    case "google" -> JavaFormatter.GOOGLE;
                    case "palantir" -> JavaFormatter.PALANTIR;
                    default -> null;
                },
                !Boolean.getBoolean("jenesis.format.rewrite"),
                Boolean.parseBoolean(System.getProperty("jenesis.format.ktlint", "true")),
                Boolean.parseBoolean(System.getProperty("jenesis.format.scalafmt", "true")));
    }

    private InferredSourceFormattingModule(Path configuration,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           Pinning pinning,
                                           JavaFormatter javaFormatter,
                                           boolean verify,
                                           boolean ktlint,
                                           boolean scalafmt) {
        this.configuration = configuration;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.javaFormatter = javaFormatter;
        this.verify = verify;
        this.ktlint = ktlint;
        this.scalafmt = scalafmt;
    }

    public InferredSourceFormattingModule pinning(Pinning pinning) {
        return new InferredSourceFormattingModule(configuration, repositories, resolvers, pinning, javaFormatter, verify, ktlint, scalafmt);
    }

    public InferredSourceFormattingModule javaFormatter(JavaFormatter javaFormatter) {
        return new InferredSourceFormattingModule(configuration, repositories, resolvers, pinning, javaFormatter, verify, ktlint, scalafmt);
    }

    public InferredSourceFormattingModule verify(boolean verify) {
        return new InferredSourceFormattingModule(configuration, repositories, resolvers, pinning, javaFormatter, verify, ktlint, scalafmt);
    }

    public InferredSourceFormattingModule ktlint(boolean ktlint) {
        return new InferredSourceFormattingModule(configuration, repositories, resolvers, pinning, javaFormatter, verify, ktlint, scalafmt);
    }

    public InferredSourceFormattingModule scalafmt(boolean scalafmt) {
        return new InferredSourceFormattingModule(configuration, repositories, resolvers, pinning, javaFormatter, verify, ktlint, scalafmt);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        if (javaFormatter != null) {
            switch (javaFormatter) {
                case GOOGLE -> buildExecutor.addModule(GOOGLE_JAVA_FORMAT,
                        new GoogleJavaFormatModule(repositories, resolvers).pinning(pinning).verify(verify),
                        inherited.sequencedKeySet());
                case PALANTIR -> buildExecutor.addModule(PALANTIR_JAVA_FORMAT,
                        new PalantirJavaFormatModule(repositories, resolvers).pinning(pinning).verify(verify),
                        inherited.sequencedKeySet());
            }
        }
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), KTLINT, ktlint,
                KtlintFormatModule.configurationFile(configuration),
                new KtlintFormatModule(repositories, resolvers).pinning(pinning).verify(verify));
        Bind.configured(buildExecutor, inherited.sequencedKeySet(), SCALAFMT, scalafmt,
                ScalafmtFormatModule.configurationFile(configuration),
                new ScalafmtFormatModule(repositories, resolvers).pinning(pinning).verify(verify));
    }
}
