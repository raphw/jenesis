package build.buildbuddy.module;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.SequencedProperties;
import build.buildbuddy.project.DependenciesModule;
import build.buildbuddy.project.MultiProjectDependencies;
import build.buildbuddy.project.MultiProjectModule;
import build.buildbuddy.step.Assign;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public static BuildExecutorModule make(Path root,
                                           String algorithm,
                                           Map<String, Repository> repositories,
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
        return make(root,
                algorithm,
                Map.of("module", new ModularJarResolver(true)),
                repositories,
                builder);
    }

    public static BuildExecutorModule make(Path root,
                                           String algorithm,
                                           Map<String, Resolver> resolvers,
                                           Map<String, Repository> repositories,
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
        return make(root,
                "module",
                _ -> true,
                algorithm,
                resolvers,
                repositories,
                builder);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           Predicate<Path> filter,
                                           String algorithm,
                                           Map<String, Resolver> resolvers,
                                           Map<String, Repository> repositories,
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
        return new MultiProjectModule(new ModularProject(prefix, root, filter),
                identity -> Optional.of(identity.substring(0, identity.indexOf('/'))),
                _ -> (name, dependencies, _) -> (buildExecutor, inherited) -> {
                    buildExecutor.addStep("prepare",
                            new MultiProjectDependencies(
                                    algorithm,
                                    identifier -> identifier.startsWith(BuildExecutorModule.PREVIOUS.repeat(3)
                                            + "identify/"
                                            + name + "/")),
                            inherited.sequencedKeySet());
                    buildExecutor.addModule("dependencies",
                            new DependenciesModule(
                                    Repository.prepend(repositories,
                                            Repository.ofProperties(BuildStep.COORDINATES,
                                                    inherited.entrySet().stream()
                                                            .filter(entry ->
                                                                    entry.getKey().startsWith(PREVIOUS + "module-")
                                                                            && entry.getKey().endsWith("/assign"))
                                                            .map(Map.Entry::getValue)
                                                            .toList(),
                                                    file -> Path.of(file).toUri(),
                                                    null)),
                                    resolvers).computeChecksums(algorithm),
                            "prepare");
                    buildExecutor.addModule("build",
                            builder.apply(
                                    name,
                                    dependencies.sequencedKeySet()),
                            Stream.concat(
                                    inherited.sequencedKeySet().stream(),
                                    Stream.of("dependencies")).collect(Collectors.toCollection(LinkedHashSet::new)));
                    buildExecutor.addStep("assign",
                            new Assign(),
                            Stream.concat(
                                    inherited.sequencedKeySet().stream().filter(identifier -> identifier.startsWith(
                                            PREVIOUS.repeat(3) + "identify/")),
                                    Stream.of("build")).collect(Collectors.toCollection(LinkedHashSet::new)));
                });
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
                            module.addSource("sources", Bind.asSources(), parent);
                            module.addStep(MultiProjectModule.MODULE, (_, context, arguments) -> {
                                ModuleInfo info = parser.identify(arguments.get("sources").folder()
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
                            }, "sources");
                        });
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.exists(dir.resolve(BuildExecutor.BUILD_MARKER))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
