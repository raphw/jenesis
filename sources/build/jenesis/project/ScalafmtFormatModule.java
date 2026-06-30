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

public class ScalafmtFormatModule implements BuildExecutorModule {

    public static final String FORMAT = "format";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "org.scalameta";
    private static final String MAVEN_ARTIFACT = "scalafmt-cli_2.13";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final String configFile;
    private final boolean verify;

    public ScalafmtFormatModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "scalafmt-format", ".scalafmt.conf", false);
    }

    private ScalafmtFormatModule(Map<String, Repository> repositories,
                                 Map<String, Resolver> resolvers,
                                 Pinning pinning,
                                 String group,
                                 String configFile,
                                 boolean verify) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.group = group;
        this.configFile = configFile;
        this.verify = verify;
    }

    public static Path configurationFile(SequencedSet<Path> configuration) {
        return BuildStep.locate(configuration, ".scalafmt.conf");
    }

    public ScalafmtFormatModule pinning(Pinning pinning) {
        return new ScalafmtFormatModule(repositories, resolvers, pinning, group, configFile, verify);
    }

    public ScalafmtFormatModule group(String group) {
        return new ScalafmtFormatModule(repositories, resolvers, pinning, group, configFile, verify);
    }

    public ScalafmtFormatModule configFile(String configFile) {
        return new ScalafmtFormatModule(repositories, resolvers, pinning, group, configFile, verify);
    }

    public ScalafmtFormatModule verify(boolean verify) {
        return new ScalafmtFormatModule(repositories, resolvers, pinning, group, configFile, verify);
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
        buildExecutor.addStep(FORMAT, new Format(group, configFile, verify), formatInputs);
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

        private final String configFile;

        private Format(String group, String configFile, boolean verify) {
            super(group, verify);
            this.configFile = configFile;
        }

        @Override
        protected boolean isFormattable(Path file) {
            return file.toString().endsWith(".scala");
        }

        @Override
        protected Path config(Path folder) {
            Path candidate = folder.resolve(configFile);
            return Files.isRegularFile(candidate) ? candidate : null;
        }

        @Override
        protected List<String> command(List<String> jars, Path config, List<String> files, boolean verify) {
            if (config == null) {
                throw new IllegalStateException("No " + configFile + " found among the inputs of the scalafmt step");
            }
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "org.scalafmt.cli.Cli",
                    "--config", config.toString()));
            if (verify) {
                commands.add("--test");
            }
            commands.addAll(files);
            return commands;
        }
    }
}
