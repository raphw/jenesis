package build.jenesis.module;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class PinModuleInfo implements BuildStep {

    private static final Pattern MODULE_DECLARATION = Pattern.compile("(?m)^(open\\s+)?module\\s+");
    private static final Pattern JAVADOC_END = Pattern.compile("\\*/\\s*$");
    private static final Pattern REQUIRES_TAG = Pattern.compile("^\\s*\\*\\s*@requires\\s+\\S+.*$");

    private final String prefix;
    private final List<Path> moduleInfoFiles;
    private final boolean fromJars;

    public PinModuleInfo(String prefix, Path moduleInfoFile) {
        this(prefix, List.of(moduleInfoFile), false);
    }

    public PinModuleInfo(String prefix, List<Path> moduleInfoFiles) {
        this(prefix, moduleInfoFiles, false);
    }

    public PinModuleInfo(String prefix, Path moduleInfoFile, boolean fromJars) {
        this(prefix, List.of(moduleInfoFile), fromJars);
    }

    public PinModuleInfo(String prefix, List<Path> moduleInfoFiles, boolean fromJars) {
        this.prefix = prefix;
        this.moduleInfoFiles = List.copyOf(moduleInfoFiles);
        this.fromJars = fromJars;
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
        SequencedMap<String, String> entries = fromJars
                ? collectFromJars(arguments)
                : collectEntries(arguments, prefix);
        for (Path file : moduleInfoFiles) {
            updateModuleInfo(file, entries);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static void updateModuleInfo(Path file, SequencedMap<String, String> entries) throws IOException {
        String existing = Files.readString(file);
        Matcher moduleMatch = MODULE_DECLARATION.matcher(existing);
        if (!moduleMatch.find()) {
            throw new IllegalStateException("No module declaration found in " + file);
        }
        int moduleStart = moduleMatch.start();
        String prelude = existing.substring(0, moduleStart);
        String body = existing.substring(moduleStart);
        String updatedPrelude = updateJavadoc(prelude, entries);
        String updated = updatedPrelude + body;
        if (!updated.equals(existing)) {
            Files.writeString(file, updated);
        }
    }

    public static SequencedMap<String, String> collectFromJars(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        Set<String> internal = collectInternal(arguments);
        SequencedMap<String, String> versionByFile = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path requiresFile = argument.folder().resolve(REQUIRES);
            if (!Files.exists(requiresFile)) {
                continue;
            }
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(requiresFile)) {
                properties.load(reader);
            }
            for (String coordinate : properties.stringPropertyNames()) {
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
                String version = coordinate.substring(lastSlash + 1);
                String checksum = properties.getProperty(coordinate);
                String value = checksum == null || checksum.isEmpty() ? version : version + " " + checksum;
                versionByFile.putIfAbsent(coordinate.replace('/', '-') + ".jar", value);
            }
        }
        SequencedMap<String, String> entries = new TreeMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path artifacts = argument.folder().resolve(BuildStep.ARTIFACTS);
            if (!Files.exists(artifacts)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts, "*.jar")) {
                for (Path jar : stream) {
                    if (!Files.isRegularFile(jar)) {
                        continue;
                    }
                    String value = versionByFile.get(jar.getFileName().toString());
                    if (value == null) {
                        continue;
                    }
                    Optional<ModuleReference> reference = ModuleFinder.of(jar).findAll().stream().findFirst();
                    if (reference.isEmpty()) {
                        continue;
                    }
                    entries.putIfAbsent(reference.get().descriptor().name(), value);
                }
            }
        }
        return entries;
    }

    static SequencedMap<String, String> collectEntries(SequencedMap<String, BuildStepArgument> arguments, String prefix) throws IOException {
        Set<String> internal = collectInternal(arguments);
        SequencedMap<String, String> entries = new TreeMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path versionsFile = argument.folder().resolve(VERSIONS);
            if (Files.exists(versionsFile)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(versionsFile)) {
                    properties.load(reader);
                }
                for (String key : properties.stringPropertyNames()) {
                    if (internal.contains(key)) {
                        continue;
                    }
                    int slash = key.indexOf('/');
                    if (slash < 0 || !prefix.equals(key.substring(0, slash))) {
                        continue;
                    }
                    entries.putIfAbsent(key.substring(slash + 1), properties.getProperty(key));
                }
            }
            Path requiresFile = argument.folder().resolve(REQUIRES);
            if (Files.exists(requiresFile)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(requiresFile)) {
                    properties.load(reader);
                }
                for (String key : properties.stringPropertyNames()) {
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
                    String checksum = properties.getProperty(key);
                    String bomValue = checksum.isEmpty() ? version : version + " " + checksum;
                    entries.putIfAbsent(bomKey, bomValue);
                }
            }
        }
        return entries;
    }

    static Set<String> collectInternal(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        Set<String> internal = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path identityFile = argument.folder().resolve(IDENTITY);
            if (!Files.exists(identityFile)) {
                continue;
            }
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(identityFile)) {
                properties.load(reader);
            }
            for (String coord : properties.stringPropertyNames()) {
                internal.add(coord);
                int firstSlash = coord.indexOf('/');
                int lastSlash = coord.lastIndexOf('/');
                if (firstSlash > 0 && lastSlash > firstSlash) {
                    internal.add(coord.substring(0, lastSlash));
                }
            }
        }
        return internal;
    }

    private static String updateJavadoc(String prelude, SequencedMap<String, String> entries) {
        int javadocEnd = -1;
        int javadocStart = -1;
        Matcher endMatch = JAVADOC_END.matcher(prelude);
        while (endMatch.find()) {
            javadocEnd = endMatch.end();
        }
        if (javadocEnd >= 0) {
            javadocStart = prelude.lastIndexOf("/**", javadocEnd);
        }
        if (javadocStart < 0 || javadocEnd < 0) {
            if (entries.isEmpty()) {
                return prelude;
            }
            return prelude + renderJavadoc(entries) + "\n";
        }
        String before = prelude.substring(0, javadocStart);
        String javadoc = prelude.substring(javadocStart, javadocEnd);
        String after = prelude.substring(javadocEnd);
        String rewritten = rewriteJavadoc(javadoc, entries);
        return before + rewritten + after;
    }

    private static String rewriteJavadoc(String javadoc, SequencedMap<String, String> entries) {
        List<String> lines = new ArrayList<>(List.of(javadoc.split("\\n", -1)));
        int insertAt = -1;
        Iterator<String> it = lines.iterator();
        int index = 0;
        while (it.hasNext()) {
            String line = it.next();
            if (REQUIRES_TAG.matcher(line).matches()) {
                if (insertAt < 0) {
                    insertAt = index;
                }
                it.remove();
            } else {
                index++;
            }
        }
        if (insertAt < 0) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).contains("*/")) {
                    insertAt = i;
                    break;
                }
            }
            if (insertAt < 0) {
                insertAt = Math.max(1, lines.size() - 1);
            }
        }
        List<String> requires = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            requires.add(" * @requires " + entry.getKey() + " " + entry.getValue());
        }
        lines.addAll(insertAt, requires);
        return String.join("\n", lines);
    }

    private static String renderJavadoc(SequencedMap<String, String> entries) {
        StringBuilder sb = new StringBuilder("/**\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append(" * @requires ").append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }
        sb.append(" */");
        return sb.toString();
    }
}
