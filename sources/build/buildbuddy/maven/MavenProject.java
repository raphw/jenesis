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
                Properties properties = new SequencedProperties();
                properties.setProperty("path", entry.getKey().toString());
                properties.setProperty("groupId", entry.getValue().groupId());
                properties.setProperty("artifactId", entry.getValue().artifactId());
                properties.setProperty("version", entry.getValue().version());
                properties.setProperty("packaging", entry.getValue().packaging() == null
                        ? "jar"
                        : entry.getValue().packaging());
                properties.setProperty("dependencies", toString(
                        entry.getValue().dependencies(),
                        MavenDependencyScope.COMPILE));
                properties.setProperty("sources", entry.getValue().sourceDirectory() == null
                        ? "src/main/java"
                        : entry.getValue().sourceDirectory());
                properties.setProperty("resources", entry.getValue().resourceDirectories() == null
                        ? "src/main/resources"
                        : String.join(",", entry.getValue().resourceDirectories()));
                try (Writer writer = Files.newBufferedWriter(maven.resolve("pom-" + URLEncoder.encode(
                        entry.getKey().toString(),
                        StandardCharsets.UTF_8) + ".properties"))) {
                    properties.store(writer, null);
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
                        Path sources = base.resolve(properties.getProperty("sources"));
                        if (Files.exists(sources)) {
                            module.addSource("sources", sources);
                            module.addStep("bind-sources", Bind.asSources(), "sources");
                        }
                        int index = 0;
                        for (String resource : properties.getProperty("resources").split(",")) {
                            Path resources = base.resolve(resource);
                            if (Files.exists(sources)) {
                                module.addSource("resources-" + ++index, resources);
                                module.addStep("bind-resources-" + index, Bind.asResources(), "resources");
                            }
                        }
                        module.addStep("declare", (_, context, _) -> {
                            Properties coordinates = new SequencedProperties();
                            coordinates.setProperty(prefix
                                    + "/" + properties.getProperty("groupId")
                                    + "/" + properties.getProperty("artifactId")
                                    + "/" + properties.getProperty("jar")
                                    + "/" + (properties.getProperty("version")), "");
                            try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(COORDINATES))) {
                                coordinates.store(writer, null);
                            }
                            Properties dependencies = new SequencedProperties();
                            for (String dependency : properties.getProperty("dependencies").split(",")) {
                                dependencies.setProperty(prefix + "/" + dependency, "");
                            }
                            try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(DEPENDENCIES))) {
                                dependencies.store(writer, null);
                            }
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        });
                    });
                }
            }
        }, "prepare");
    }

    private static String toString(SequencedMap<MavenDependencyKey, MavenDependencyValue> values,
                                   MavenDependencyScope scope) {
        return values == null ? "" : values.entrySet().stream()
                .filter(dependency -> dependency.getValue().scope().reduces(scope))
                .map(entry -> entry.getKey().groupId()
                        + "/" + entry.getKey().artifactId()
                        + "/" + entry.getValue().version()
                        + "/" + (entry.getKey().type() == null ? "jar" : entry.getKey().type())
                        + (entry.getKey().classifier() == null ? "" : "/" + entry.getKey().classifier()))
                .collect(Collectors.joining(","));
    }
}
