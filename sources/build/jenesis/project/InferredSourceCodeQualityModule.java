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
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        if (hasFile(inherited, "checkstyle.xml")) {
            buildExecutor.addModule(CHECKSTYLE,
                    new CheckstyleModule(repositories, resolvers).pinning(pinning), upstream);
        }
        if (hasFile(inherited, "pmd.xml")) {
            buildExecutor.addModule(PMD,
                    new PmdModule(repositories, resolvers).pinning(pinning), upstream);
        }
        if (hasFile(inherited, "detekt.yml")) {
            buildExecutor.addModule(DETEKT,
                    new DetektModule(repositories, resolvers).pinning(pinning), upstream);
        }
        if (hasFile(inherited, ".editorconfig")) {
            buildExecutor.addModule(KTLINT,
                    new KtlintModule(repositories, resolvers).pinning(pinning), upstream);
        }
        if (hasFile(inherited, "scalastyle-config.xml")) {
            buildExecutor.addModule(SCALASTYLE,
                    new ScalastyleModule(repositories, resolvers).pinning(pinning), upstream);
        }
        if (hasFile(inherited, ".scalafmt.conf")) {
            buildExecutor.addModule(SCALAFMT,
                    new ScalafmtModule(repositories, resolvers).pinning(pinning), upstream);
        }
        if (hasFile(inherited, "codenarc.xml")) {
            buildExecutor.addModule(CODENARC,
                    new CodeNarcModule(repositories, resolvers).pinning(pinning), upstream);
        }
    }

    private static boolean hasFile(SequencedMap<String, Path> inherited, String fileName) {
        for (Path folder : inherited.values()) {
            if (Files.isRegularFile(folder.resolve(fileName))) {
                return true;
            }
        }
        return false;
    }
}
