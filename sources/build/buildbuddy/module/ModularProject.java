package build.buildbuddy.module;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.SequencedProperties;
import build.buildbuddy.step.Bind;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public class ModularProject implements BuildExecutorModule {

    private final String prefix;
    private final Path root;
    private final Predicate<Path> filter;

    private final ModuleInfoParser parser = new ModuleInfoParser();

    public ModularProject(String prefix, Path root, Predicate<Path> filter) {
        this.prefix = prefix;
        this.root = root;
        this.filter = filter;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("module-info.java")) {
                    Path parent = file.getParent(), location = root.relativize(parent);
                    if (filter.test(location)) {
                        buildExecutor.addModule("module-" + URLEncoder.encode(
                                location.toString(),
                                StandardCharsets.UTF_8), (module, _) -> {
                            module.addSource("path", parent);
                            module.addStep("source", Bind.asSources(), "path");
                            module.addStep("module", (_, context, arguments) -> {
                                ModuleInfo info = parser.identify(arguments.get("source").folder()
                                        .resolve(BuildStep.SOURCES)
                                        .resolve("module-info.java"));
                                Properties coordinates = new SequencedProperties();
                                coordinates.setProperty(prefix + "/" + info.coordinate(), "");
                                try (BufferedWriter writer = Files.newBufferedWriter(context
                                        .next()
                                        .resolve(BuildStep.COORDINATES))) {
                                    coordinates.store(writer, null);
                                }
                                Properties dependencies = new SequencedProperties();
                                for (String dependency : info.requires()) {
                                    dependencies.setProperty(prefix + "/" + dependency, "");
                                }
                                try (BufferedWriter writer = Files.newBufferedWriter(context
                                        .next()
                                        .resolve(BuildStep.DEPENDENCIES))) {
                                    dependencies.store(writer, null);
                                }
                                return CompletableFuture.completedStage(new BuildStepResult(true));
                            }, "source");
                        });

                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
