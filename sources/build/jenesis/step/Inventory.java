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
                Path.of(DEPENDENCIES),
                Path.of(JPackage.PACKAGES),
                Path.of(JMod.JMODS),
                Path.of(JLink.RUNTIME),
                Path.of(TEST_REPORT)));
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
        Path image = null;
        Path runtimeImage = null;
        SequencedSet<Path> artifacts = new LinkedHashSet<>();
        SequencedSet<Path> sources = new LinkedHashSet<>();
        SequencedSet<Path> documentation = new LinkedHashSet<>();
        SequencedSet<Path> jmods = new LinkedHashSet<>();
        SequencedSet<Path> testReports = new LinkedHashSet<>();
        SequencedMap<String, Path> closureJars = new LinkedHashMap<>();
        SequencedMap<String, String> closureScopes = new LinkedHashMap<>();
        SequencedMap<String, String> closureChecksums = new LinkedHashMap<>();
        SequencedSet<String> identity = new LinkedHashSet<>();
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
            Path identityFile = folder.resolve(IDENTITY);
            if (Files.isRegularFile(identityFile)) {
                identity.addAll(SequencedProperties.ofFiles(identityFile).stringPropertyNames());
            }
            Path pomCandidate = folder.resolve(POM);
            if (pomFile == null && Files.isRegularFile(pomCandidate)) {
                pomFile = pomCandidate;
            }
            collect(folder.resolve(ARTIFACTS), artifacts);
            collect(folder.resolve(SOURCES), sources);
            collect(folder.resolve(DOCUMENTATION), documentation);
            Path packages = folder.resolve(JPackage.PACKAGES);
            if (image == null && Files.isDirectory(packages)) {
                image = packages;
            }
            collect(folder.resolve(JMod.JMODS), jmods);
            collect(folder.resolve(TEST_REPORT), testReports);
            Path runtime = folder.resolve(JLink.RUNTIME);
            if (runtimeImage == null && Files.isDirectory(runtime)) {
                runtimeImage = runtime;
            }
            collectClosure(folder, closureJars, closureScopes, closureChecksums);
        }
        String prefix = ((path == null || path.isEmpty()) ? "module" : "module-" + path) + ".";
        SequencedProperties inventory = new SequencedProperties();
        SequencedSet<Path> runtime = new LinkedHashSet<>(artifacts);
        for (Map.Entry<String, Path> entry : closureJars.entrySet()) {
            if (!isToolScope(closureScopes.get(entry.getKey()))) {
                runtime.add(entry.getValue());
            }
        }
        int identityIndex = 0;
        for (String coordinate : identity) {
            inventory.setProperty(prefix + "identity." + identityIndex++, coordinate);
        }
        int dependencyIndex = 0;
        for (Map.Entry<String, Path> entry : closureJars.entrySet()) {
            String checksum = closureChecksums.get(entry.getKey());
            inventory.setProperty(prefix + "dependency." + dependencyIndex++,
                    entry.getKey() + " " + relativize(context, entry.getValue())
                            + (checksum == null || checksum.isEmpty() ? "" : " " + checksum));
        }
        writePaths(inventory, context, prefix + "artifacts", artifacts);
        writePaths(inventory, context, prefix + "sources", sources);
        writePaths(inventory, context, prefix + "documentation", documentation);
        writePaths(inventory, context, prefix + "jmod", jmods);
        writePaths(inventory, context, prefix + "testreport", testReports);
        if (image != null) {
            inventory.setProperty(prefix + "package", relativize(context, image));
        }
        if (runtimeImage != null) {
            inventory.setProperty(prefix + "image", relativize(context, runtimeImage));
        }
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
        if (path != null) {
            inventory.setProperty(prefix + "path", path);
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

    public static String prefixOf(String path) {
        return ((path == null || path.isEmpty()) ? "module" : "module-" + path) + ".";
    }

    public record Dependency(Path jar, String checksum) {
    }

    public static SequencedMap<String, Dependency> closure(Iterable<BuildStepArgument> arguments, String path) throws IOException {
        String key = prefixOf(path) + "dependency.";
        SequencedMap<String, Dependency> closure = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments) {
            Path inventoryFile = argument.folder().resolve(INVENTORY);
            if (!Files.isRegularFile(inventoryFile)) {
                continue;
            }
            SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
            for (int index = 0; ; index++) {
                String value = inventory.getProperty(key + index);
                if (value == null) {
                    break;
                }
                String[] parts = value.split(" ", 3);
                closure.putIfAbsent(parts[0], new Dependency(
                        argument.folder().resolve(parts[1]).normalize(),
                        parts.length > 2 ? parts[2] : ""));
            }
        }
        return closure;
    }

    public static SequencedSet<String> modulePaths(Iterable<BuildStepArgument> arguments) throws IOException {
        SequencedSet<String> paths = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments) {
            Path inventoryFile = argument.folder().resolve(INVENTORY);
            if (!Files.isRegularFile(inventoryFile)) {
                continue;
            }
            SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
            for (String key : inventory.stringPropertyNames()) {
                if (key.endsWith(".path")) {
                    paths.add(inventory.getProperty(key));
                }
            }
        }
        return paths;
    }

    public static Set<String> identities(Iterable<BuildStepArgument> arguments) throws IOException {
        Set<String> identities = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments) {
            Path inventoryFile = argument.folder().resolve(INVENTORY);
            if (!Files.isRegularFile(inventoryFile)) {
                continue;
            }
            SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
            for (String key : inventory.stringPropertyNames()) {
                if (key.contains(".identity.")) {
                    identities.add(inventory.getProperty(key));
                }
            }
        }
        return identities;
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
                if (Files.isRegularFile(file)) {
                    sink.add(file);
                }
            }
        }
    }

    private static void collectClosure(Path folder,
                                       SequencedMap<String, Path> jars,
                                       SequencedMap<String, String> scopes,
                                       SequencedMap<String, String> checksums) throws IOException {
        Path requiresFile = folder.resolve(REQUIRES);
        if (Files.isRegularFile(requiresFile)) {
            SequencedProperties required = SequencedProperties.ofFiles(requiresFile);
            for (String coordinate : required.stringPropertyNames()) {
                String value = required.getProperty(coordinate);
                if (!value.isEmpty()) {
                    checksums.putIfAbsent(coordinate, value);
                }
            }
        }
        Path locationsFile = folder.resolve(LOCATIONS);
        if (!Files.isRegularFile(locationsFile)) {
            return;
        }
        SequencedProperties locations = SequencedProperties.ofFiles(locationsFile);
        Path scopesFile = folder.resolve(SCOPES);
        SequencedProperties scoped = Files.isRegularFile(scopesFile)
                ? SequencedProperties.ofFiles(scopesFile)
                : new SequencedProperties();
        for (String coordinate : locations.stringPropertyNames()) {
            Path file = folder.resolve(locations.getProperty(coordinate)).normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            jars.putIfAbsent(coordinate, file);
            String scope = scoped.getProperty(coordinate);
            String prior = scopes.get(coordinate);
            if (prior == null || isToolScope(prior)) {
                scopes.put(coordinate, scope == null ? "" : scope);
            }
        }
    }

    private static boolean isToolScope(String scope) {
        // A scope carrying a ':' namespace (e.g. compiler:kotlin, module:tool) marks a build-tool
        // closure, which is never a runtime dependency of the produced module.
        return scope != null && scope.indexOf(':') >= 0;
    }

    private static String relativize(BuildStepContext context, Path file) {
        return context.next().relativize(file).toString().replace(File.separatorChar, '/');
    }
}
