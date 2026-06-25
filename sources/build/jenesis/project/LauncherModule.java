package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.PathPlacement;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;

public class LauncherModule implements BuildExecutorModule {

    public static final String BUNDLE = "bundle";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final PathPlacement pathPlacement;

    public LauncherModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "launcher", PathPlacement.INFERRED);
    }

    private LauncherModule(Map<String, Repository> repositories,
                           Map<String, Resolver> resolvers,
                           Pinning pinning,
                           String group,
                           PathPlacement pathPlacement) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.group = group;
        this.pathPlacement = pathPlacement;
    }

    public LauncherModule pinning(Pinning pinning) {
        return new LauncherModule(repositories, resolvers, pinning, group, pathPlacement);
    }

    public LauncherModule group(String group) {
        return new LauncherModule(repositories, resolvers, pinning, group, pathPlacement);
    }

    public LauncherModule pathPlacement(PathPlacement pathPlacement) {
        return new LauncherModule(repositories, resolvers, pinning, group, pathPlacement);
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
        SequencedSet<String> bundleInputs = new LinkedHashSet<>();
        bundleInputs.add(DEPENDENCIES);
        bundleInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(BUNDLE, new build.jenesis.step.Launcher(group, pathPlacement), bundleInputs);
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
            requires.setProperty(group + "/runtime/maven/build.jenesis/build.jenesis.launcher/RELEASE", "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
