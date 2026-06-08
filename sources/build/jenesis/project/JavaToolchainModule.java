package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Versions;

public record JavaToolchainModule(BuildExecutorModule compiler,
                                  BuildExecutorModule transformer,
                                  BuildExecutorModule validator,
                                  BuildExecutorModule archiver) implements BuildExecutorModule {

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes", TRANSFORM = "transform", VALIDATE = "validate";
    private static final String COMPILED = "compiled";

    public JavaToolchainModule() {
        this(new Javac(ProcessHandler.Factory.of()).asModule("javac"), null, null, new Jar(ProcessHandler.Factory.of(), Jar.Sort.CLASSES).asModule("jar"));
    }

    public JavaToolchainModule compiler(BuildExecutorModule compiler) {
        return new JavaToolchainModule(compiler, transformer, validator, archiver);
    }

    public JavaToolchainModule group(String group) {
        return compiler(new Javac(ProcessHandler.Factory.of()).group(group).asModule("javac"));
    }

    public JavaToolchainModule archiver(BuildExecutorModule archiver) {
        return new JavaToolchainModule(compiler, transformer, validator, archiver);
    }

    public JavaToolchainModule transformer(BuildExecutorModule transformer) {
        return new JavaToolchainModule(compiler, transformer, validator, archiver);
    }

    public JavaToolchainModule validator(BuildExecutorModule validator) {
        return new JavaToolchainModule(compiler, transformer, validator, archiver);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addModule(COMPILED, compiler, inherited.sequencedKeySet());
        buildExecutor.addStep(CLASSES, new Versions(), Stream.concat(
                Stream.of(COMPILED),
                inherited.sequencedKeySet().stream()));
        String classes;
        if (transformer == null) {
            classes = CLASSES;
        } else {
            buildExecutor.addModule(TRANSFORM, transformer, Stream.concat(
                    Stream.of(CLASSES),
                    inherited.sequencedKeySet().stream()));
            classes = TRANSFORM;
        }
        if (validator != null) {
            buildExecutor.addModule(VALIDATE, validator, Stream.concat(
                    Stream.of(classes),
                    inherited.sequencedKeySet().stream()));
        }
        buildExecutor.addModule(ARTIFACTS, archiver, Stream.concat(
                Stream.of(classes),
                inherited.sequencedKeySet().stream()));
    }

    @Override
    public Optional<String> resolve(String path) {
        return switch (path) {
            case COMPILED -> Optional.empty();
            case TRANSFORM -> Optional.of(CLASSES);
            case CLASSES -> transformer == null ? Optional.of(CLASSES) : Optional.empty();
            default -> Optional.of(path);
        };
    }
}
