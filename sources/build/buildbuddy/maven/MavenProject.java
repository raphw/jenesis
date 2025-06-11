package build.buildbuddy.maven;

import build.buildbuddy.*;
import build.buildbuddy.project.DependenciesModule;
import build.buildbuddy.project.MultiProjectDependencies;
import build.buildbuddy.project.MultiProjectModule;
import build.buildbuddy.step.Assign;
import build.buildbuddy.step.Bind;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static build.buildbuddy.BuildStep.COORDINATES;
import static build.buildbuddy.BuildStep.DEPENDENCIES;

public class MavenProject implements BuildExecutorModule {

    public static final String POM = "pom/", MAVEN = "maven/";

    private final Path root;
    private final String prefix;
    private final MavenRepository repository;
    private final MavenPomResolver resolver;

    public MavenProject(Path root, String prefix, MavenRepository repository, MavenPomResolver resolver) {
        this.root = root;
        this.prefix = prefix;
        this.repository = repository;
        this.resolver = resolver;
    }

    public static BuildExecutorModule make(Path root,
                                           String algorithm,
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
        return make(root, "maven", algorithm, new MavenDefaultRepository(), new MavenPomResolver(), builder);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           String algorithm,
                                           MavenRepository mavenRepository,
                                           MavenPomResolver mavenResolver,
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
        return new MultiProjectModule(new MavenProject(root, prefix, mavenRepository, mavenResolver),
                identifier -> identifier.startsWith("module/")
                        ? Optional.of(identifier.substring(7, identifier.indexOf('/', 7)))
                        : Optional.empty(),
                _ -> (name, dependencies, _) -> (buildExecutor, inherited) -> {
                    buildExecutor.addStep("prepare",
                            new MultiProjectDependencies(
                                    algorithm,
                                    identifier -> identifier.startsWith(BuildExecutorModule.PREVIOUS.repeat(3)
                                            + "identify/"
                                            + "module/"
                                            + name + "/")),
                            inherited.sequencedKeySet());
                    buildExecutor.addModule("dependencies",
                            new DependenciesModule(
                                    Repository.prepend(Map.of(prefix, mavenRepository),
                                            Repository.ofProperties(BuildStep.COORDINATES,
                                                    inherited.entrySet().stream()
                                                            .filter(entry ->
                                                                    entry.getKey().startsWith(PREVIOUS + "module-")
                                                                            && entry.getKey().endsWith("/assign"))
                                                            .map(Map.Entry::getValue)
                                                            .toList(),
                                                    file -> Path.of(file).toUri(),
                                                    null)),
                                    Map.of(prefix, mavenResolver)).computeChecksums(algorithm),
                            "prepare");
                    buildExecutor.addModule("build",
                            builder.apply(name, dependencies.sequencedKeySet()),
                            Stream.concat(inherited.sequencedKeySet().stream(), Stream.of("dependencies")));
                    buildExecutor.addStep("assign",
                            new Assign(),
                            Stream.concat(
                                    inherited.sequencedKeySet().stream().filter(identifier -> identifier.startsWith(
                                            PREVIOUS.repeat(3) + "identify/")),
                                    Stream.of("build")));
                });
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        if (!Files.exists(root.resolve("pom.xml"))) {
            return;
        }
        buildExecutor.addStep("scan", new BuildStep() {
            @Override
            public CompletionStage<BuildStepResult> apply(Executor executor,
                                                          BuildStepContext context,
                                                          SequencedMap<String, BuildStepArgument> arguments)
                    throws IOException {
                Path poms = Files.createDirectory(context.next().resolve(POM));
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().equals("pom.xml")) {
                            Path target = poms.resolve(root.relativize(file));
                            Files.createDirectories(target.getParent());
                            Files.createLink(target, file);
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
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }

            @Override
            public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
                return true;
            }
        });
        buildExecutor.addStep("prepare", (executor, context, arguments) -> {
            Path maven = Files.createDirectory(context.next().resolve(MAVEN));
            for (Map.Entry<Path, MavenLocalPom> entry : resolver.local(executor,
                    repository,
                    arguments.get("scan")
                            .folder()
                            .resolve(POM)).entrySet()) {
                if (Objects.equals("pom", entry.getValue().packaging())) {
                    continue;
                }
                String coordinate = prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/" + (entry.getValue().packaging() == null ? "jar" : entry.getValue().packaging())
                        + "/" + entry.getValue().version();
                Properties module = new SequencedProperties();
                module.setProperty("coordinate", coordinate);
                module.setProperty("pom", prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/pom"
                        + "/" + entry.getValue().version());
                module.setProperty("path", entry.getKey().toString());
                module.setProperty("groupId", entry.getValue().groupId());
                module.setProperty("artifactId", entry.getValue().artifactId());
                module.setProperty("version", entry.getValue().version());
                module.setProperty("type", entry.getValue().packaging() == null
                        ? "jar"
                        : entry.getValue().packaging());
                module.setProperty("dependencies", toDependencies(
                        entry.getValue().dependencies(),
                        Set.of(MavenDependencyScope.COMPILE, MavenDependencyScope.PROVIDED)));
                module.setProperty("sources", entry.getValue().sourceDirectory() == null
                        ? "src/main/java"
                        : entry.getValue().sourceDirectory());
                module.setProperty("resources", entry.getValue().resourceDirectories() == null
                        ? "src/main/resources"
                        : entry.getValue().resourceDirectories().stream().sorted().collect(Collectors.joining(",")));
                try (Writer writer = Files.newBufferedWriter(maven.resolve("module-" + URLEncoder.encode(
                        entry.getKey().toString(),
                        StandardCharsets.UTF_8) + ".properties"))) {
                    module.store(writer, null);
                }
                Properties testModule = new SequencedProperties();
                testModule.setProperty("coordinate", prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/" + (entry.getValue().packaging() == null ? "jar" : entry.getValue().packaging())
                        + "/tests"
                        + "/" + entry.getValue().version());
                testModule.setProperty("pom", prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/pom"
                        + "/" + entry.getValue().version());
                testModule.setProperty("path", entry.getKey().toString());
                String dependencies = toDependencies(
                        entry.getValue().dependencies(),
                        Set.of(MavenDependencyScope.TEST, MavenDependencyScope.RUNTIME));
                testModule.setProperty("dependencies", dependencies.isEmpty()
                        ? coordinate
                        : dependencies + "," + coordinate);
                testModule.setProperty("sources", entry.getValue().testSourceDirectory() == null
                        ? "src/test/java"
                        : entry.getValue().testSourceDirectory());
                testModule.setProperty("resources", entry.getValue().testResourceDirectories() == null
                        ? "src/test/resources"
                        : entry.getValue().testResourceDirectories().stream().sorted().collect(Collectors.joining(",")));
                try (Writer writer = Files.newBufferedWriter(maven.resolve("test-module-" + URLEncoder.encode(
                        entry.getKey().toString(),
                        StandardCharsets.UTF_8) + ".properties"))) {
                    testModule.store(writer, null);
                }
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "scan");
        buildExecutor.addModule(MultiProjectModule.MODULE, (modules, paths) -> {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(
                    paths.get("../prepare").resolve(MAVEN),
                    "*.properties")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    modules.addModule(name.substring(0, name.length() - 11), (module, _) -> {
                        Properties properties = new SequencedProperties();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            properties.load(reader);
                        }
                        boolean active = false;
                        Path base = root.resolve(properties.getProperty("path"));
                        if (!properties.getProperty("sources").isEmpty()) {
                            Path sources = base.resolve(properties.getProperty("sources"));
                            if (Files.exists(sources)) {
                                module.addSource("sources", Bind.asSources(), sources);
                                active = true;
                            }
                        }
                        int index = 0;
                        if (!properties.getProperty("resources").isEmpty()) {
                            for (String resource : properties.getProperty("resources").split(",")) {
                                Path resources = base.resolve(resource);
                                if (Files.exists(resources)) {
                                    module.addSource("resources-" + ++index, Bind.asResources(), resources);
                                    active = true;
                                }
                            }
                        }
                        if (active) {
                            module.addStep("declare", (_, context, _) -> {
                                Properties coordinates = new SequencedProperties();
                                coordinates.setProperty(properties.getProperty("coordinate"), "");
                                coordinates.setProperty(properties.getProperty("pom"), paths.get("../scan")
                                        .resolve(POM)
                                        .resolve(properties.getProperty("path"))
                                        .resolve("pom.xml")
                                        .toString());
                                try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(COORDINATES))) {
                                    coordinates.store(writer, null);
                                }
                                Properties dependencies = new SequencedProperties();
                                if (!properties.getProperty("dependencies").isEmpty()) {
                                    for (String dependency : properties.getProperty("dependencies").split(",")) {
                                        dependencies.setProperty(dependency, "");
                                    }
                                }
                                try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(DEPENDENCIES))) {
                                    dependencies.store(writer, null);
                                }
                                return CompletableFuture.completedStage(new BuildStepResult(true));
                            });
                        }
                    });
                }
            }
        }, "scan", "prepare");
    }

    private String toDependencies(SequencedMap<MavenDependencyKey, MavenDependencyValue> values,
                                  Set<MavenDependencyScope> scopes) {
        return values == null ? "" : values.entrySet().stream()
                .filter(dependency -> scopes.contains(dependency.getValue().scope()))
                .map(entry -> prefix
                        + "/" + entry.getKey().groupId()
                        + "/" + entry.getKey().artifactId()
                        + "/" + (entry.getKey().type() == null ? "jar" : entry.getKey().type())
                        + (entry.getKey().classifier() == null ? "" : "/" + entry.getKey().classifier())
                        + "/" + entry.getValue().version())
                .collect(Collectors.joining(","));
    }
}
