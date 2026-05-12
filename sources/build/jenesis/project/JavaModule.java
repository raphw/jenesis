package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.TestEngine;
import build.jenesis.step.Versions;

public record JavaModule(boolean process) implements BuildExecutorModule {

    public JavaModule() {
        this(false);
    }

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes", VERSIONS = "versions", TEST = "test";

    public BuildExecutorModule testIfAvailable() {
        return test(null, null, null);
    }

    public BuildExecutorModule testIfAvailable(Map<String, Repository> repositories,
                                               Map<String, Resolver> resolvers) {
        return test(null, repositories, resolvers);
    }

    public BuildExecutorModule test(TestEngine engine) {
        return test(engine, null, null);
    }

    public BuildExecutorModule test(TestEngine engine,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        return (buildExecutor, inherited) -> {
            TestEngine candidate = engine;
            if (candidate == null) {
                candidate = TestEngine.of(() -> inherited.values().stream().iterator()).orElse(null);
            }
            accept(buildExecutor, inherited);
            if (candidate != null) {
                TestModule tests = new TestModule(candidate);
                if (repositories != null && resolvers != null) {
                    tests = tests.withResolvers(repositories, resolvers);
                }
                buildExecutor.addModule(TEST, tests, Stream.concat(
                        Stream.of(CLASSES, ARTIFACTS),
                        inherited.sequencedKeySet().stream().filter(key -> !hasSegment(key, MultiProjectModule.COMPILE))));
            }
        };
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> compileScope = inherited.sequencedKeySet().stream()
                .filter(key -> !hasSegment(key, MultiProjectModule.RUNTIME))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        buildExecutor.addStep(CLASSES, process ? Javac.process() : Javac.tool(), compileScope);
        buildExecutor.addStep(VERSIONS, new Versions(), Stream.concat(
                Stream.of(CLASSES),
                compileScope.stream()));
        buildExecutor.addStep(ARTIFACTS, process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES), Stream.concat(
                Stream.of(VERSIONS),
                compileScope.stream()));
    }

    private static boolean hasSegment(String key, String segment) {
        for (String part : key.split("/")) {
            if (part.equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
