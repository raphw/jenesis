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
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(MODULE),
                Path.of(METADATA),
                Path.of(POM),
                Path.of(ARTIFACTS),
                Path.of(SOURCES),
                Path.of(DOCUMENTATION),
                Path.of(DEPENDENCIES)));
    }

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
        boolean modular = false;
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
                    tests = properties.getProperty("test");
                }
                modular |= Boolean.parseBoolean(properties.getProperty("modular"));
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
        writePaths(inventory, context, prefix + "artifacts", artifacts);
        writePaths(inventory, context, prefix + "sources", sources);
        writePaths(inventory, context, prefix + "documentation", documentation);
        if (pomFile != null) {
            inventory.setProperty(prefix + "pom", relativize(context, pomFile));
        }
        if (version != null) {
            inventory.setProperty(prefix + "version", version);
        }
        if (tests != null) {
            inventory.setProperty(prefix + "test", tests);
        }
        if (mainClass != null) {
            inventory.setProperty(prefix + "mainClass", mainClass);
        }
        if (modular && module != null) {
            inventory.setProperty(prefix + "module", module);
        }
        writePaths(inventory, context, prefix + "runtime", runtime);
        if (!inventory.isEmpty()) {
            inventory.store(context.next().resolve(INVENTORY));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static void writePaths(SequencedProperties inventory,
                                   BuildStepContext context,
                                   String key,
                                   Collection<Path> files) {
        int index = 0;
        for (Path file : files) {
            inventory.setProperty(key + "." + index, relativize(context, file));
            index++;
        }
    }

    public static List<Path> paths(SequencedProperties inventory, Path folder, String key) {
        List<Path> resolved = new ArrayList<>();
        for (int index = 0; ; index++) {
            String value = inventory.getProperty(key + "." + index);
            if (value == null) {
                return resolved;
            }
            resolved.add(folder.resolve(value).normalize());
        }
    }

    private static void collect(Path folder, SequencedSet<Path> sink) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                // A '@' in the file name marks a qualified resolution trail (a compiler or
                // build-module tool closure), which is never a dependency of the produced module.
                if (Files.isRegularFile(file) && file.getFileName().toString().indexOf('@') < 0) {
                    sink.add(file);
                }
            }
        }
    }

    private static String relativize(BuildStepContext context, Path file) {
        return context.next().relativize(file).toString().replace(File.separatorChar, '/');
    }
}
