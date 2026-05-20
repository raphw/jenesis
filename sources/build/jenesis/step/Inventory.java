package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Inventory implements BuildStep {

    public static final String INVENTORY = "inventory.properties";
    public static final String POM = "pom.xml";
    public static final String CLASSES_JAR = "classes.jar";
    public static final String SOURCES_JAR = "sources.jar";
    public static final String JAVADOC_JAR = "javadoc.jar";

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        String path = null;
        String mainClass = null;
        String module = null;
        String tests = null;
        String version = null;
        Path identityArtifact = null;
        Path classesArtifact = null;
        Path sourcesArtifact = null;
        Path javadocArtifact = null;
        Path pomArtifact = null;
        SequencedSet<Path> dependencies = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path folder = argument.folder();
            Path moduleFile = folder.resolve(MODULE);
            if (Files.isRegularFile(moduleFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(moduleFile);
                if (path == null) {
                    path = properties.getProperty("path");
                }
                if (mainClass == null) {
                    mainClass = properties.getProperty("main");
                }
                if (module == null) {
                    module = properties.getProperty("module");
                }
                if (tests == null) {
                    tests = properties.getProperty("tests");
                }
            }
            Path metadataFile = folder.resolve(METADATA);
            if (Files.isRegularFile(metadataFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(metadataFile);
                if (version == null) {
                    version = properties.getProperty("version");
                }
            }
            Path identityFile = folder.resolve(IDENTITY);
            if (identityArtifact == null && Files.isRegularFile(identityFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(identityFile);
                boolean complete = true;
                for (String name : properties.stringPropertyNames()) {
                    if (properties.getProperty(name).isEmpty()) {
                        complete = false;
                        break;
                    }
                }
                if (complete) {
                    for (String name : properties.stringPropertyNames()) {
                        Path resolved = folder.resolve(properties.getProperty(name)).normalize();
                        if (Files.isRegularFile(resolved)) {
                            identityArtifact = resolved;
                            break;
                        }
                    }
                }
            }
            Path pomFile = folder.resolve(POM);
            if (pomArtifact == null && Files.isRegularFile(pomFile)) {
                pomArtifact = pomFile;
            }
            Path artifactsDir = folder.resolve(ARTIFACTS);
            if (Files.isDirectory(artifactsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifactsDir)) {
                    for (Path file : stream) {
                        String name = file.getFileName().toString();
                        switch (name) {
                            case CLASSES_JAR -> {
                                if (classesArtifact == null) {
                                    classesArtifact = file;
                                } else {
                                    dependencies.add(file);
                                }
                            }
                            case SOURCES_JAR -> {
                                if (sourcesArtifact == null) {
                                    sourcesArtifact = file;
                                } else {
                                    dependencies.add(file);
                                }
                            }
                            case JAVADOC_JAR -> {
                                if (javadocArtifact == null) {
                                    javadocArtifact = file;
                                } else {
                                    dependencies.add(file);
                                }
                            }
                            default -> dependencies.add(file);
                        }
                    }
                }
            }
        }
        Path artifact = classesArtifact != null ? classesArtifact : identityArtifact;
        String prefix = ((path == null || path.isEmpty()) ? "module" : "module-" + path) + ".";
        SequencedProperties inventory = new SequencedProperties();
        SequencedSet<Path> runtime = new LinkedHashSet<>();
        if (artifact != null) {
            runtime.add(artifact);
        }
        runtime.addAll(dependencies);
        if (artifact != null) {
            inventory.setProperty(prefix + "artifact", relativize(context, artifact));
        }
        if (sourcesArtifact != null) {
            inventory.setProperty(prefix + "artifact.sources", relativize(context, sourcesArtifact));
        }
        if (javadocArtifact != null) {
            inventory.setProperty(prefix + "artifact.javadoc", relativize(context, javadocArtifact));
        }
        if (pomArtifact != null) {
            inventory.setProperty(prefix + "pom", relativize(context, pomArtifact));
        }
        if (version != null) {
            inventory.setProperty(prefix + "version", version);
        }
        if (tests != null) {
            inventory.setProperty(prefix + "tests", tests);
        }
        if (mainClass != null) {
            inventory.setProperty(prefix + "mainClass", mainClass);
        }
        if (module != null) {
            inventory.setProperty(prefix + "module", module);
        }
        if (!runtime.isEmpty()) {
            inventory.setProperty(prefix + "runtime", runtime.stream()
                    .map(file -> relativize(context, file))
                    .collect(Collectors.joining(",")));
        }
        if (!inventory.isEmpty()) {
            inventory.store(context.next().resolve(INVENTORY));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String relativize(BuildStepContext context, Path file) {
        return context.next().relativize(file).toString().replace(File.separatorChar, '/');
    }
}
