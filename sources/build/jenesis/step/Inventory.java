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
            String scope = closureScopes.get(entry.getKey());
            if (scope != null && List.of(scope.split(",")).contains("runtime")) {
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
            inventory.setProperty(prefix + "dependency." + dependencyIndex,
                    entry.getKey() + " " + relativize(context, entry.getValue())
                            + (checksum == null || checksum.isEmpty() ? "" : " " + checksum));
            String scope = closureScopes.get(entry.getKey());
            if (scope != null) {
                inventory.setProperty(prefix + "dependency." + dependencyIndex + ".scope", scope);
            }
            dependencyIndex++;
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

    public record Dependency(Path jar, String checksum, String scope) {
        public String group(String defaultGroup) {
            return scope == null || scope.contains("compile") || scope.contains("runtime") ? defaultGroup : scope;
        }
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
                        parts.length > 2 ? parts[2] : "",
                        inventory.getProperty(key + index + ".scope")));
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
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }
        files.sort(Comparator.comparing(file -> file.getFileName().toString()));
        sink.addAll(files);
    }

    private static void collectClosure(Path folder,
                                       SequencedMap<String, Path> jars,
                                       SequencedMap<String, String> scopes,
                                       SequencedMap<String, String> checksums) throws IOException {
        Path indexFile = folder.resolve(DEPENDENCIES);
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        SequencedProperties index = SequencedProperties.ofFiles(indexFile);
        for (String key : index.stringPropertyNames()) {
            int slash = key.indexOf('/');
            String scope = key.substring(0, slash), coordinate = key.substring(slash + 1);
            String value = index.getProperty(key);
            int space = value.indexOf(' ');
            Path file = folder.resolve(space < 0 ? value : value.substring(0, space)).normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            if (space >= 0) {
                checksums.putIfAbsent(coordinate, value.substring(space + 1));
            }
            jars.putIfAbsent(coordinate, file);
            String prior = scopes.get(coordinate);
            if (prior == null) {
                scopes.put(coordinate, scope);
            } else if (!List.of(prior.split(",")).contains(scope)) {
                scopes.put(coordinate, prior + "," + scope);
            }
        }
    }

    private static String relativize(BuildStepContext context, Path file) {
        return context.next().relativize(file).toString().replace(File.separatorChar, '/');
    }
}
