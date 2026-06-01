package build.jenesis.module;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;

public class PinModuleInfo implements BuildStep {

    private static final Pattern MODULE_DECLARATION = Pattern.compile("(?m)^(open\\s+)?module\\s+");
    private static final Pattern JAVADOC_END = Pattern.compile("\\*/\\s*$");
    private static final Pattern PIN_TAG = Pattern.compile("^\\s*\\*\\s*@jenesis\\.pin\\s+\\S+.*$");

    private final String prefix;
    private final String path;
    private final List<Path> moduleInfoFiles;
    private final boolean fromJars;
    private final transient HashDigestFunction hashFunction;

    public PinModuleInfo(String prefix, String path, Path moduleInfoFile, HashDigestFunction hashFunction) {
        this(prefix, path, List.of(moduleInfoFile), false, hashFunction);
    }

    public PinModuleInfo(String prefix, String path, List<Path> moduleInfoFiles, HashDigestFunction hashFunction) {
        this(prefix, path, moduleInfoFiles, false, hashFunction);
    }

    public PinModuleInfo(String prefix, String path, Path moduleInfoFile, boolean fromJars, HashDigestFunction hashFunction) {
        this(prefix, path, List.of(moduleInfoFile), fromJars, hashFunction);
    }

    public PinModuleInfo(String prefix, String path, List<Path> moduleInfoFiles, boolean fromJars, HashDigestFunction hashFunction) {
        this.prefix = prefix;
        this.path = path;
        this.moduleInfoFiles = List.copyOf(moduleInfoFiles);
        this.fromJars = fromJars;
        this.hashFunction = hashFunction;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return true;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, Inventory.Dependency> closure = Inventory.closure(arguments.values(), path);
        Set<String> internal = collectInternal(Inventory.identities(arguments.values()));
        SequencedMap<String, String> entries = fromJars
                ? collectFromJars(closure, internal, hashFunction)
                : collectEntries(closure, internal, prefix, hashFunction);
        SequencedMap<String, String> qualified = collectQualified(closure, internal, hashFunction);
        for (Path file : moduleInfoFiles) {
            updateModuleInfo(file, entries, qualified);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String computeChecksum(Inventory.Dependency dependency,
                                          HashDigestFunction hashFunction) throws IOException {
        if (dependency.jar() != null && Files.isRegularFile(dependency.jar())) {
            return hashFunction.encodedHash(dependency.jar());
        }
        return dependency.checksum().isEmpty() ? null : dependency.checksum();
    }

    private static void updateModuleInfo(Path file, SequencedMap<String, String> entries, SequencedMap<String, String> qualified) throws IOException {
        String existing = Files.readString(file);
        Matcher moduleDeclarationMatcher = MODULE_DECLARATION.matcher(existing);
        if (!moduleDeclarationMatcher.find()) {
            throw new IllegalStateException("No module declaration found in " + file);
        }
        int moduleStart = moduleDeclarationMatcher.start();
        String prelude = existing.substring(0, moduleStart);
        String body = existing.substring(moduleStart);
        String updatedPrelude = updateJavadoc(prelude, entries, qualified);
        String updated = updatedPrelude + body;
        if (!updated.equals(existing)) {
            Files.writeString(file, updated);
        }
    }

    public static SequencedMap<String, String> collectFromJars(SequencedMap<String, Inventory.Dependency> closure,
                                                               Set<String> internal,
                                                               HashDigestFunction hashFunction) throws IOException {
        SequencedMap<String, String> entries = new TreeMap<>();
        for (Map.Entry<String, Inventory.Dependency> dependency : closure.entrySet()) {
            String coordinate = dependency.getKey();
            if (internal.contains(coordinate)) {
                continue;
            }
            int firstSlash = coordinate.indexOf('/');
            int lastSlash = coordinate.lastIndexOf('/');
            if (firstSlash <= 0 || lastSlash == firstSlash) {
                // Skip coordinates with no version segment (e.g. "module/foo"); the last
                // segment must be a real version, not the module/artifact name.
                continue;
            }
            if (coordinate.substring(0, firstSlash).indexOf('@') > 0) {
                continue;
            }
            Path jar = dependency.getValue().jar();
            if (jar == null || !Files.isRegularFile(jar)) {
                continue;
            }
            String version = coordinate.substring(lastSlash + 1);
            String checksum = computeChecksum(dependency.getValue(), hashFunction);
            String value = checksum == null ? version : version + " " + checksum;
            Optional<ModuleReference> reference = ModuleFinder.of(jar).findAll().stream().findFirst();
            if (reference.isEmpty()) {
                continue;
            }
            ModuleDescriptor descriptor = reference.get().descriptor();
            if (descriptor.isAutomatic() && !hasAutomaticModuleName(jar)) {
                // A plain jar has no real module name to pin against; record it under its
                // explicit coordinate (e.g. maven/org.jetbrains/annotations) instead.
                entries.putIfAbsent(coordinate.substring(0, lastSlash), value);
                continue;
            }
            entries.putIfAbsent(descriptor.name(), value);
        }
        return entries;
    }

    private static boolean hasAutomaticModuleName(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            return manifest != null
                    && manifest.getMainAttributes().getValue("Automatic-Module-Name") != null;
        }
    }

    static SequencedMap<String, String> collectEntries(SequencedMap<String, Inventory.Dependency> closure,
                                                       Set<String> internal,
                                                       String prefix,
                                                       HashDigestFunction hashFunction) throws IOException {
        SequencedMap<String, String> entries = new TreeMap<>();
        for (Map.Entry<String, Inventory.Dependency> dependency : closure.entrySet()) {
            String key = dependency.getKey();
            if (internal.contains(key)) {
                continue;
            }
            int slash = key.indexOf('/');
            if (slash < 0 || !prefix.equals(key.substring(0, slash))) {
                continue;
            }
            String suffix = key.substring(slash + 1);
            int lastSlash = suffix.lastIndexOf('/');
            if (lastSlash <= 0) {
                continue;
            }
            String bomKey = suffix.substring(0, lastSlash);
            String version = suffix.substring(lastSlash + 1);
            String checksum = computeChecksum(dependency.getValue(), hashFunction);
            entries.putIfAbsent(bomKey, checksum == null ? version : version + " " + checksum);
        }
        return entries;
    }

    static SequencedMap<String, String> collectQualified(SequencedMap<String, Inventory.Dependency> closure,
                                                         Set<String> internal,
                                                         HashDigestFunction hashFunction) throws IOException {
        SequencedMap<String, String> entries = new TreeMap<>();
        for (Map.Entry<String, Inventory.Dependency> dependency : closure.entrySet()) {
            String key = dependency.getKey();
            if (internal.contains(key)) {
                continue;
            }
            int slash = key.indexOf('/');
            if (slash < 0) {
                continue;
            }
            int at = key.substring(0, slash).indexOf('@');
            if (at < 1) {
                continue;
            }
            String suffix = key.substring(slash + 1);
            int lastSlash = suffix.lastIndexOf('/');
            if (lastSlash <= 0) {
                continue;
            }
            String prefix = key.substring(0, at);
            String token = (prefix.equals("module") ? "@" : prefix + "@")
                    + key.substring(at + 1, slash) + "/" + suffix.substring(0, lastSlash);
            String version = suffix.substring(lastSlash + 1);
            String checksum = computeChecksum(dependency.getValue(), hashFunction);
            entries.putIfAbsent(token, checksum == null ? version : version + " " + checksum);
        }
        return entries;
    }

    static Set<String> collectInternal(Set<String> identities) {
        Set<String> internal = new LinkedHashSet<>();
        for (String coord : identities) {
            internal.add(coord);
            int firstSlash = coord.indexOf('/');
            int lastSlash = coord.lastIndexOf('/');
            if (firstSlash > 0 && lastSlash > firstSlash) {
                internal.add(coord.substring(0, lastSlash));
            }
        }
        return internal;
    }

    private static String updateJavadoc(String prelude, SequencedMap<String, String> entries, SequencedMap<String, String> qualified) {
        int javadocEnd = -1;
        int javadocStart = -1;
        Matcher javadocEndMatcher = JAVADOC_END.matcher(prelude);
        while (javadocEndMatcher.find()) {
            javadocEnd = javadocEndMatcher.end();
        }
        if (javadocEnd >= 0) {
            javadocStart = prelude.lastIndexOf("/**", javadocEnd);
        }
        if (javadocStart < 0 || javadocEnd < 0) {
            if (entries.isEmpty() && qualified.isEmpty()) {
                return prelude;
            }
            return prelude + renderJavadoc(entries, qualified) + "\n";
        }
        String before = prelude.substring(0, javadocStart);
        String javadoc = prelude.substring(javadocStart, javadocEnd);
        String after = prelude.substring(javadocEnd);
        String rewritten = rewriteJavadoc(javadoc, entries, qualified);
        return before + rewritten + after;
    }

    private static String rewriteJavadoc(String javadoc, SequencedMap<String, String> entries, SequencedMap<String, String> qualified) {
        List<String> lines = new ArrayList<>(List.of(javadoc.split("\\n", -1)));
        int insertAt = -1;
        Iterator<String> it = lines.iterator();
        int index = 0;
        while (it.hasNext()) {
            String line = it.next();
            if (PIN_TAG.matcher(line).matches()) {
                if (insertAt < 0) {
                    insertAt = index;
                }
                it.remove();
            } else {
                index++;
            }
        }
        if (insertAt < 0) {
            for (int lineIndex = lines.size() - 1; lineIndex >= 0; lineIndex--) {
                if (lines.get(lineIndex).contains("*/")) {
                    insertAt = lineIndex;
                    break;
                }
            }
            if (insertAt < 0) {
                insertAt = Math.max(1, lines.size() - 1);
            }
        }
        List<String> tags = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            tags.add(" * @jenesis.pin " + entry.getKey() + " " + entry.getValue());
        }
        for (Map.Entry<String, String> entry : qualified.entrySet()) {
            tags.add(" * @jenesis.pin " + entry.getKey() + " " + entry.getValue());
        }
        lines.addAll(insertAt, tags);
        return String.join("\n", lines);
    }

    private static String renderJavadoc(SequencedMap<String, String> entries, SequencedMap<String, String> qualified) {
        StringBuilder sb = new StringBuilder("/**\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append(" * @jenesis.pin ").append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }
        for (Map.Entry<String, String> entry : qualified.entrySet()) {
            sb.append(" * @jenesis.pin ").append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }
        sb.append(" */");
        return sb.toString();
    }
}
