package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;

public class InferredSourceFormattingModule implements BuildExecutorModule {

    public enum JavaFormatter {
        NONE, GOOGLE, PALANTIR
    }

    public static final String GOOGLE_JAVA_FORMAT = "google-java-format", PALANTIR_JAVA_FORMAT = "palantir-java-format",
            KTLINT = "ktlint-format", SCALAFMT = "scalafmt-format";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final JavaFormatter javaFormatter;
    private final boolean verify;

    public InferredSourceFormattingModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, JavaFormatter.NONE, false);
    }

    private InferredSourceFormattingModule(Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           Pinning pinning,
                                           JavaFormatter javaFormatter,
                                           boolean verify) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.javaFormatter = javaFormatter;
        this.verify = verify;
    }

    public InferredSourceFormattingModule pinning(Pinning pinning) {
        return new InferredSourceFormattingModule(repositories, resolvers, pinning, javaFormatter, verify);
    }

    public InferredSourceFormattingModule javaFormatter(JavaFormatter javaFormatter) {
        return new InferredSourceFormattingModule(repositories, resolvers, pinning, javaFormatter, verify);
    }

    public InferredSourceFormattingModule verify(boolean verify) {
        return new InferredSourceFormattingModule(repositories, resolvers, pinning, javaFormatter, verify);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        switch (javaFormatter) {
            case GOOGLE -> buildExecutor.addModule(GOOGLE_JAVA_FORMAT,
                    new GoogleJavaFormatModule(repositories, resolvers).pinning(pinning).verify(verify),
                    inherited.sequencedKeySet());
            case PALANTIR -> buildExecutor.addModule(PALANTIR_JAVA_FORMAT,
                    new PalantirJavaFormatModule(repositories, resolvers).pinning(pinning).verify(verify),
                    inherited.sequencedKeySet());
            case NONE -> {
            }
        }
        if (KtlintFormatModule.isConfigured(inherited)) {
            buildExecutor.addModule(KTLINT,
                    new KtlintFormatModule(repositories, resolvers).pinning(pinning).verify(verify),
                    inherited.sequencedKeySet());
        }
        if (ScalafmtFormatModule.isConfigured(inherited)) {
            buildExecutor.addModule(SCALAFMT,
                    new ScalafmtFormatModule(repositories, resolvers).pinning(pinning).verify(verify),
                    inherited.sequencedKeySet());
        }
    }
}
