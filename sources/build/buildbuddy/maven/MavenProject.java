package build.buildbuddy.maven;

import build.buildbuddy.*;
import build.buildbuddy.step.Bind;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static build.buildbuddy.BuildStep.COORDINATES;
import static build.buildbuddy.BuildStep.DEPENDENCIES;

public class MavenProject implements BuildExecutorDelegate {

    public static final String POMS = "poms/", MAVEN = "maven/";

    private final String prefix;
    private final Path root;
    private final MavenPomResolver resolver;

    public MavenProject(String prefix, Path root, MavenPomResolver resolver) {
        this.prefix = prefix;
        this.root = root;
        this.resolver = resolver;
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
                Path poms = Files.createDirectory(context.next().resolve(POMS));
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
                });
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }

            @Override
            public boolean isAlwaysRun() {
                return true;
            }
        });
        buildExecutor.addStep("prepare", (_, context, arguments) -> {
            Path maven = Files.createDirectory(context.next().resolve(MAVEN));
            for (Map.Entry<Path, MavenLocalPom> entry : resolver.local(arguments.get("scan")
                    .folder()
                    .resolve(POMS)).entrySet()) {
                if (Objects.equals("pom", entry.getValue().packaging())) {
                    continue;
                }
                Properties module = new SequencedProperties();
                module.setProperty("path", entry.getKey().toString());
                module.setProperty("groupId", entry.getValue().groupId());
                module.setProperty("artifactId", entry.getValue().artifactId());
                module.setProperty("version", entry.getValue().version());
                module.setProperty("type", entry.getValue().packaging() == null
                        ? "jar"
                        : entry.getValue().packaging());
                module.setProperty("dependencies", toString(
                        entry.getValue().dependencies(),
                        MavenDependencyScope.COMPILE));
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
                testModule.setProperty("path", entry.getKey().toString());
                testModule.setProperty("groupId", entry.getValue().groupId());
                testModule.setProperty("artifactId", entry.getValue().artifactId());
                testModule.setProperty("version", entry.getValue().version());
                testModule.setProperty("type", entry.getValue().packaging() == null
                        ? "jar"
                        : entry.getValue().packaging());
                testModule.setProperty("classifier", "test-jar");
                testModule.setProperty("dependencies", toString(
                        entry.getValue().dependencies(),
                        MavenDependencyScope.TEST));
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
        buildExecutor.add("define", (modules, paths) -> {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(
                    paths.get("prepare").resolve(MAVEN),
                    "*.properties")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    modules.add(name.substring(0, name.length() - 11), (module, _) -> {
                        Properties properties = new SequencedProperties();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            properties.load(reader);
                        }
                        Path base = root.resolve(properties.getProperty("path"));
                        boolean declared = false;
                        if (!properties.getProperty("sources").isEmpty()) {
                            Path sources = base.resolve(properties.getProperty("sources"));
                            if (Files.exists(sources)) {
                                module.addSource("path-sources", sources);
                                module.addStep("sources", Bind.asSources(), "path-sources");
                                declared = true;
                            }
                        }
                        int index = 0;
                        if (!properties.getProperty("resources").isEmpty()) {
                            for (String resource : properties.getProperty("resources").split(",")) {
                                Path resources = base.resolve(resource);
                                if (Files.exists(resources)) {
                                    module.addSource("path-resources-" + ++index, resources);
                                    module.addStep("resources-" + index, Bind.asResources(), "path-resources-" + index);
                                    declared = true;
                                }
                            }
                        }
                        if (declared) {
                            module.addStep("declare", (_, context, _) -> {
                                Properties coordinates = new SequencedProperties();
                                coordinates.setProperty(prefix
                                        + "/" + properties.getProperty("groupId")
                                        + "/" + properties.getProperty("artifactId")
                                        + "/" + properties.getProperty("type")
                                        + (properties.containsKey("classifier") ? "/" + properties.getProperty("classifier") : "")
                                        + "/" + (properties.getProperty("version")), "");
                                try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(COORDINATES))) {
                                    coordinates.store(writer, null);
                                }
                                Properties dependencies = new SequencedProperties();
                                if (!properties.getProperty("dependencies").isEmpty()) {
                                    for (String dependency : properties.getProperty("dependencies").split(",")) {
                                        dependencies.setProperty(prefix + "/" + dependency, "");
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
        }, "prepare");
    }

    private static String toString(SequencedMap<MavenDependencyKey, MavenDependencyValue> values,
                                   MavenDependencyScope scope) {
        return values == null ? "" : values.entrySet().stream()
                .filter(dependency -> !dependency.getValue().scope().reduces(scope))
                .map(entry -> entry.getKey().groupId()
                        + "/" + entry.getKey().artifactId()
                        + "/" + (entry.getKey().type() == null ? "jar" : entry.getKey().type())
                        + (entry.getKey().classifier() == null ? "" : "/" + entry.getKey().classifier())
                        + "/" + entry.getValue().version())
                .collect(Collectors.joining(","));
    }
}
