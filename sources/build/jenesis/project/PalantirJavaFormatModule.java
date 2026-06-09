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

public class PalantirJavaFormatModule implements BuildExecutorModule {

    public static final String FORMAT = "format";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "com.palantir.javaformat";
    private static final String MAVEN_ARTIFACT = "palantir-java-format";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final boolean verify;

    public PalantirJavaFormatModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "palantir-java-format", false);
    }

    private PalantirJavaFormatModule(Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers,
                                     Pinning pinning,
                                     String group,
                                     boolean verify) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.group = group;
        this.verify = verify;
    }

    public PalantirJavaFormatModule pinning(Pinning pinning) {
        return new PalantirJavaFormatModule(repositories, resolvers, pinning, group, verify);
    }

    public PalantirJavaFormatModule group(String group) {
        return new PalantirJavaFormatModule(repositories, resolvers, pinning, group, verify);
    }

    public PalantirJavaFormatModule verify(boolean verify) {
        return new PalantirJavaFormatModule(repositories, resolvers, pinning, group, verify);
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
        buildExecutor.addStep(FORMAT, new Format(group, verify), formatInputs);
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

        private Format(String group, boolean verify) {
            super(group, verify);
        }

        @Override
        protected boolean isFormattable(Path file) {
            return file.toString().endsWith(".java")
                    && !file.getFileName().toString().equals("module-info.java");
        }

        @Override
        protected List<String> command(List<String> jars, Path config, List<String> files, boolean verify) {
            List<String> commands = new ArrayList<>(List.of(
                    "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                    "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                    "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                    "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                    "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                    "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                    "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                    "-cp", String.join(File.pathSeparator, jars),
                    "com.palantir.javaformat.java.Main"));
            if (verify) {
                commands.add("--dry-run");
                commands.add("--set-exit-if-changed");
            } else {
                commands.add("--replace");
            }
            commands.addAll(files);
            return commands;
        }
    }
}
