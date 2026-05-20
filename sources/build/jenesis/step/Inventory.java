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
        Path pomFile = null;
        SequencedSet<Path> artifacts = new LinkedHashSet<>();
        SequencedSet<Path> sources = new LinkedHashSet<>();
        SequencedSet<Path> documentation = new LinkedHashSet<>();
        SequencedSet<Path> dependencies = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path folder = argument.folder();
            Path moduleProperties = folder.resolve(MODULE);
            if (Files.isRegularFile(moduleProperties)) {
                SequencedProperties properties = SequencedProperties.ofFiles(moduleProperties);
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
            Path pomCandidate = folder.resolve(POM);
            if (pomFile == null && Files.isRegularFile(pomCandidate)) {
                pomFile = pomCandidate;
            }
            collect(folder.resolve(ARTIFACTS), artifacts);
            collect(folder.resolve(SOURCES), sources);
            collect(folder.resolve(DOCUMENTATION), documentation);
            collect(folder.resolve(DEPENDENCIES), dependencies);
        }
        String prefix = ((path == null || path.isEmpty()) ? "module" : "module-" + path) + ".";
        SequencedProperties inventory = new SequencedProperties();
        SequencedSet<Path> runtime = new LinkedHashSet<>();
        runtime.addAll(artifacts);
        runtime.addAll(dependencies);
        if (!artifacts.isEmpty()) {
            inventory.setProperty(prefix + "artifacts", relativize(context, artifacts));
        }
        if (!sources.isEmpty()) {
            inventory.setProperty(prefix + "sources", relativize(context, sources));
        }
        if (!documentation.isEmpty()) {
            inventory.setProperty(prefix + "documentation", relativize(context, documentation));
        }
        if (pomFile != null) {
            inventory.setProperty(prefix + "pom", relativize(context, pomFile));
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
            inventory.setProperty(prefix + "runtime", relativize(context, runtime));
        }
        if (!inventory.isEmpty()) {
            inventory.store(context.next().resolve(INVENTORY));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static void collect(Path folder, SequencedSet<Path> sink) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    sink.add(file);
                }
            }
        }
    }

    private static String relativize(BuildStepContext context, Path file) {
        return context.next().relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static String relativize(BuildStepContext context, Collection<Path> files) {
        return files.stream().map(file -> relativize(context, file)).collect(Collectors.joining(","));
    }
}
