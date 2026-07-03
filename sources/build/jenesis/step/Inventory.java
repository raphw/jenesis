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

    private final String group;

    public Inventory() {
        this("main");
    }

    private Inventory(String group) {
        this.group = group;
    }

    public Inventory group(String group) {
        return new Inventory(group);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(MODULE),
                Path.of(METADATA),
                Path.of(POM),
                Path.of(ARTIFACTS),
                Path.of(Bom.BOM),
                Path.of(SOURCES),
                Path.of(DOCUMENTATION),
                Path.of(DEPENDENCIES),
                Path.of(BOMS),
                Path.of("graph.properties"),
                Path.of("licenses.properties"),
                Path.of(JPackage.PACKAGES),
                Path.of(JMod.JMODS),
                Path.of(JLink.RUNTIME),
                Path.of(NativeImage.NATIVE),
                Path.of(NativeImage.METADATA),
                Path.of(REPORTS)));
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
        String artifact = null;
        Path pomFile = null;
        boolean modular = false;
        Path image = null;
        Path runtimeImage = null;
        Path nativeBinary = null;
        Path metadataImage = null;
        SequencedSet<Path> artifacts = new LinkedHashSet<>();
        SequencedSet<Path> bomArtifacts = new LinkedHashSet<>();
        SequencedSet<Path> sources = new LinkedHashSet<>();
        SequencedSet<Path> documentation = new LinkedHashSet<>();
        SequencedSet<Path> jmods = new LinkedHashSet<>();
        SequencedMap<String, Path> reports = new LinkedHashMap<>();
        SequencedSet<Path> graphs = new LinkedHashSet<>();
        SequencedSet<Path> dependencyLicenses = new LinkedHashSet<>();
        SequencedMap<String, Path> closureJars = new LinkedHashMap<>();
        SequencedMap<String, String> closureScopes = new LinkedHashMap<>();
        SequencedMap<String, String> closureChecksums = new LinkedHashMap<>();
        SequencedMap<String, Path> bomFiles = new LinkedHashMap<>();
        SequencedMap<String, String> bomValues = new LinkedHashMap<>();
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
                if (artifact == null) {
                    artifact = properties.getProperty("artifact");
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
            collect(folder.resolve(Bom.BOM), bomArtifacts);
            collect(folder.resolve(SOURCES), sources);
            collect(folder.resolve(DOCUMENTATION), documentation);
            Path packages = folder.resolve(JPackage.PACKAGES);
            if (image == null && Files.isDirectory(packages)) {
                image = packages;
            }
            collect(folder.resolve(JMod.JMODS), jmods);
            Path reportsFolder = folder.resolve(REPORTS);
            if (Files.isDirectory(reportsFolder)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsFolder)) {
                    for (Path kind : stream) {
                        if (Files.isDirectory(kind)) {
                            reports.putIfAbsent(kind.getFileName().toString(), kind);
                        }
                    }
                }
            }
            Path graphFile = folder.resolve("graph.properties");
            if (Files.isRegularFile(graphFile)) {
                graphs.add(graphFile);
            }
            Path licensesFile = folder.resolve("licenses.properties");
            if (Files.isRegularFile(licensesFile)) {
                dependencyLicenses.add(licensesFile);
            }
            Path runtime = folder.resolve(JLink.RUNTIME);
            if (runtimeImage == null && Files.isDirectory(runtime)) {
                runtimeImage = runtime;
            }
            Path nativeOutput = folder.resolve(NativeImage.NATIVE);
            if (nativeBinary == null && Files.isDirectory(nativeOutput)) {
                nativeBinary = nativeOutput;
            }
            Path metadata = folder.resolve(NativeImage.METADATA);
            if (metadataImage == null && Files.isDirectory(metadata)) {
                metadataImage = metadata;
            }
            collectClosure(folder, closureJars, closureScopes, closureChecksums);
            Path bomsFile = folder.resolve(BOMS);
            if (Files.isRegularFile(folder.resolve(DEPENDENCIES)) && Files.isRegularFile(bomsFile)) {
                SequencedProperties resolvedBoms = SequencedProperties.ofFiles(bomsFile);
                for (String key : resolvedBoms.stringPropertyNames()) {
                    if (key.startsWith("bom/")) {
                        bomFiles.putIfAbsent(key, folder.resolve(resolvedBoms.getProperty(key)).normalize());
                    } else if (key.startsWith("entry/")) {
                        bomValues.putIfAbsent(key, resolvedBoms.getProperty(key));
                    }
                }
            }
        }
        String prefix = ((path == null || path.isEmpty()) ? "module" : "module-" + path) + ".";
        SequencedProperties inventory = new SequencedProperties();
        SequencedSet<Path> runtime = new LinkedHashSet<>(artifacts);
        for (Map.Entry<String, Path> entry : closureJars.entrySet()) {
            String group = entry.getKey().substring(0, entry.getKey().indexOf('/'));
            String scope = closureScopes.get(entry.getKey());
            if (group.equals(this.group) && scope != null && List.of(scope.split(",")).contains("runtime")) {
                runtime.add(entry.getValue());
            }
        }
        int identityIndex = 0;
        for (String coordinate : identity) {
            inventory.setProperty(prefix + "identity." + identityIndex++, coordinate);
        }
        int dependencyIndex = 0;
        for (Map.Entry<String, Path> entry : closureJars.entrySet()) {
            int slash = entry.getKey().indexOf('/');
            String group = entry.getKey().substring(0, slash);
            String coordinate = entry.getKey().substring(slash + 1);
            String checksum = closureChecksums.get(entry.getKey());
            inventory.setProperty(prefix + "dependency." + dependencyIndex,
                    coordinate + " " + relativize(context, entry.getValue())
                            + (checksum == null || checksum.isEmpty() ? "" : " " + checksum));
            String scope = closureScopes.get(entry.getKey());
            if (scope != null) {
                inventory.setProperty(prefix + "dependency." + dependencyIndex + ".scope", scope);
            }
            inventory.setProperty(prefix + "dependency." + dependencyIndex + ".group", group);
            dependencyIndex++;
        }
        int bomIndex = 0;
        for (Map.Entry<String, Path> entry : bomFiles.entrySet()) {
            inventory.setProperty(prefix + "bom." + bomIndex++,
                    entry.getKey() + " " + relativize(context, entry.getValue()));
        }
        for (Map.Entry<String, String> entry : bomValues.entrySet()) {
            inventory.setProperty(prefix + "bom." + bomIndex++, entry.getKey() + " " + entry.getValue());
        }
        writePaths(inventory, context, prefix + "artifacts", artifacts);
        writePaths(inventory, context, prefix + "bomfile", bomArtifacts);
        writePaths(inventory, context, prefix + "sources", sources);
        writePaths(inventory, context, prefix + "documentation", documentation);
        writePaths(inventory, context, prefix + "jmod", jmods);
        for (Map.Entry<String, Path> entry : reports.entrySet()) {
            inventory.setProperty(prefix + "report." + entry.getKey(), relativize(context, entry.getValue()));
        }
        writePaths(inventory, context, prefix + "graph", graphs);
        if (!graphs.isEmpty()) {
            inventory.setProperty(prefix + "report.dependencies", relativize(context, graphs.getFirst()));
        }
        writePaths(inventory, context, prefix + "licenses", dependencyLicenses);
        if (image != null) {
            inventory.setProperty(prefix + "package", relativize(context, image));
        }
        if (runtimeImage != null) {
            inventory.setProperty(prefix + "image", relativize(context, runtimeImage));
        }
        if (nativeBinary != null) {
            inventory.setProperty(prefix + "native", relativize(context, nativeBinary));
        }
        if (metadataImage != null) {
            inventory.setProperty(prefix + "nativeimage", relativize(context, metadataImage));
        }
        if (pomFile != null) {
            inventory.setProperty(prefix + "pom", relativize(context, pomFile));
        }
        if (version != null) {
            inventory.setProperty(prefix + "version", version);
        }
        if (artifact != null) {
            inventory.setProperty(prefix + "artifact", artifact);
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

    public record Dependency(Path jar, String checksum, String scope, String group) {
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
                String group = inventory.getProperty(key + index + ".group");
                if (group == null) {
                    group = "main";
                }
                closure.putIfAbsent(group + "/" + parts[0], new Dependency(
                        argument.folder().resolve(parts[1]).normalize(),
                        parts.length > 2 ? parts[2] : "",
                        inventory.getProperty(key + index + ".scope"),
                        group));
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

    public static SequencedMap<String, Path> bomReferences(Iterable<BuildStepArgument> arguments, String path)
            throws IOException {
        SequencedMap<String, Path> references = new LinkedHashMap<>();
        forEachBom(arguments, path, (key, value, folder) -> {
            if (key.startsWith("bom/")) {
                references.putIfAbsent(key.substring(4), folder.resolve(value).normalize());
            }
        });
        return references;
    }

    public static SequencedMap<String, String> bomEntries(Iterable<BuildStepArgument> arguments, String path)
            throws IOException {
        SequencedMap<String, String> entries = new LinkedHashMap<>();
        forEachBom(arguments, path, (key, value, _) -> {
            if (key.startsWith("entry/")) {
                entries.putIfAbsent(key.substring(6), value);
            }
        });
        return entries;
    }

    private interface BomConsumer {

        void accept(String key, String value, Path folder);
    }

    private static void forEachBom(Iterable<BuildStepArgument> arguments,
                                   String path,
                                   BomConsumer consumer) throws IOException {
        String key = prefixOf(path) + "bom.";
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
                int space = value.indexOf(' ');
                if (space < 1) {
                    continue;
                }
                consumer.accept(value.substring(0, space), value.substring(space + 1), argument.folder());
            }
        }
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
            int firstSlash = key.indexOf('/');
            int secondSlash = firstSlash < 0 ? -1 : key.indexOf('/', firstSlash + 1);
            if (secondSlash < 0) {
                continue;
            }
            String group = key.substring(0, firstSlash);
            String scope = key.substring(firstSlash + 1, secondSlash);
            String coordinate = key.substring(secondSlash + 1);
            String entry = group + "/" + coordinate;
            String value = index.getProperty(key);
            int space = value.indexOf(' ');
            Path file = folder.resolve(space < 0 ? value : value.substring(0, space)).normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            if (space >= 0) {
                checksums.putIfAbsent(entry, value.substring(space + 1));
            }
            jars.putIfAbsent(entry, file);
            String prior = scopes.get(entry);
            if (prior == null) {
                scopes.put(entry, scope);
            } else if (!List.of(prior.split(",")).contains(scope)) {
                scopes.put(entry, prior + "," + scope);
            }
        }
    }

    private static String relativize(BuildStepContext context, Path file) {
        return context.next().relativize(file).toString().replace(File.separatorChar, '/');
    }
}
