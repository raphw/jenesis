package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;

public class Inventory implements BuildStep {

    public static final String INVENTORY = "inventory.properties";
    public static final String POM = "pom.xml";

    private final HashDigestFunction digest;

    public Inventory(HashDigestFunction digest) {
        this.digest = digest;
    }

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
        String primaryHash = writePaths(inventory, context, digest, prefix + "artifacts", artifacts);
        writePaths(inventory, context, digest, prefix + "sources", sources);
        writePaths(inventory, context, digest, prefix + "documentation", documentation);
        if (pomFile != null) {
            inventory.setProperty(prefix + "pom.path", relativize(context, pomFile));
            inventory.setProperty(prefix + "pom.hash", digest.encodedHash(pomFile));
        }
        if (version != null) {
            inventory.setProperty(prefix + "version", primaryHash == null ? version : version + " " + primaryHash);
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
        writePaths(inventory, context, digest, prefix + "runtime", runtime);
        if (!inventory.isEmpty()) {
            inventory.store(context.next().resolve(INVENTORY));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String writePaths(SequencedProperties inventory,
                                     BuildStepContext context,
                                     HashDigestFunction digest,
                                     String key,
                                     Collection<Path> files) throws IOException {
        String first = null;
        int index = 0;
        for (Path file : files) {
            String hash = digest.encodedHash(file);
            if (index == 0) {
                first = hash;
            }
            inventory.setProperty(key + "." + index + ".path", relativize(context, file));
            inventory.setProperty(key + "." + index + ".hash", hash);
            index++;
        }
        return first;
    }

    public static List<Path> paths(SequencedProperties inventory, Path folder, String key) {
        List<Path> resolved = new ArrayList<>();
        for (int index = 0; ; index++) {
            String value = inventory.getProperty(key + "." + index + ".path");
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
                if (Files.isRegularFile(file)) {
                    sink.add(file);
                }
            }
        }
    }

    private static String relativize(BuildStepContext context, Path file) {
        return context.next().relativize(file).toString().replace(File.separatorChar, '/');
    }
}
