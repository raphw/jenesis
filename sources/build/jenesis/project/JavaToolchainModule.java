package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.Versions;

public record JavaToolchainModule(BuildExecutorModule compiler, BuildExecutorModule archiver) implements BuildExecutorModule {

    public JavaToolchainModule() {
        this(Javac.tool().asModule("javac"), Jar.tool(Jar.Sort.CLASSES).asModule("jar"));
    }

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes";
    private static final String COMPILED = "compiled";

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addModule(COMPILED, compiler, inherited.sequencedKeySet());
        buildExecutor.addStep(CLASSES, new Versions(), Stream.concat(
                Stream.of(COMPILED),
                inherited.sequencedKeySet().stream()));
        buildExecutor.addModule(ARTIFACTS, archiver, Stream.concat(
                Stream.of(CLASSES),
                inherited.sequencedKeySet().stream()));
    }

    @Override
    public Optional<String> resolve(String path) {
        return path.equals(COMPILED) ? Optional.empty() : Optional.of(path);
    }
}
