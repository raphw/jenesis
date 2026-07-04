package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;
import build.jenesis.step.FormatBuildStep;
import build.jenesis.step.ProcessBuildStep;

public class KtlintFormatModule implements BuildExecutorModule {

    public static final String FORMAT = "format";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "com.pinterest.ktlint";
    private static final String MAVEN_ARTIFACT = "ktlint-cli";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final boolean verify;
    private final Boolean printing;

    public KtlintFormatModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "ktlint-format", false, null);
    }

    private KtlintFormatModule(Map<String, Repository> repositories,
                               Map<String, Resolver> resolvers,
                               Pinning pinning,
                               String group,
                               boolean verify,
                               Boolean printing) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.group = group;
        this.verify = verify;
        this.printing = printing;
    }

    public static Path configurationFile(SequencedSet<Path> configuration) {
        return BuildStep.locate(configuration, ".editorconfig");
    }

    public KtlintFormatModule pinning(Pinning pinning) {
        return new KtlintFormatModule(repositories, resolvers, pinning, group, verify, printing);
    }

    public KtlintFormatModule group(String group) {
        return new KtlintFormatModule(repositories, resolvers, pinning, group, verify, printing);
    }

    public KtlintFormatModule verify(boolean verify) {
        return new KtlintFormatModule(repositories, resolvers, pinning, group, verify, printing);
    }

    public KtlintFormatModule printing(boolean printing) {
        return new KtlintFormatModule(repositories, resolvers, pinning, group, verify, printing);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(REQUIRED, new Requires(group), inherited.sequencedKeySet());
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> formatInputs = new LinkedHashSet<>();
        formatInputs.add(DEPENDENCIES);
        formatInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(FORMAT, new Format(group, verify, printing), formatInputs);
    }

    private record Requires(String group) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return false;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(group + "/runtime/maven/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/RELEASE", "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Format extends FormatBuildStep {

        private Format(String group, boolean verify, Boolean printing) {
            super(group, verify, printing == null ? ProcessBuildStep.printing(group) : printing);
        }

        @Override
        protected boolean isFormattable(Path file) {
            return file.toString().endsWith(".kt");
        }

        @Override
        protected Path config(Path folder) {
            Path candidate = folder.resolve(".editorconfig");
            return Files.isRegularFile(candidate) ? candidate : null;
        }

        @Override
        protected List<String> command(List<String> jars, Path config, List<String> files, boolean verify) {
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "com.pinterest.ktlint.Main"));
            if (config != null) {
                commands.add("--editorconfig=" + config);
            }
            if (!verify) {
                commands.add("-F");
            }
            commands.addAll(files);
            return commands;
        }
    }
}
