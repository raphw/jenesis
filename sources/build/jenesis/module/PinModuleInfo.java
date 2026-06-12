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
    private static final Pattern PIN_TAG = Pattern.compile("^\\s*\\*\\s*@jenesis\\.pin\\s+(\\S+)(\\s+.*)?$");

    private final String prefix;
    private final String path;
    private final List<Path> moduleInfoFiles;
    private final transient HashDigestFunction hashFunction;

    public PinModuleInfo(String prefix, String path, Path moduleInfoFile, HashDigestFunction hashFunction) {
        this(prefix, path, List.of(moduleInfoFile), hashFunction);
    }

    public PinModuleInfo(String prefix, String path, List<Path> moduleInfoFiles, HashDigestFunction hashFunction) {
        this.prefix = prefix;
        this.path = path;
        this.moduleInfoFiles = List.copyOf(moduleInfoFiles);
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
        SequencedMap<String, String> entries = collectEntries(closure, internal, hashFunction);
        for (Path file : moduleInfoFiles) {
            updateModuleInfo(file, entries);
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

    private static void updateModuleInfo(Path file, SequencedMap<String, String> entries) throws IOException {
        String existing = Files.readString(file);
        Matcher moduleDeclarationMatcher = MODULE_DECLARATION.matcher(existing);
        if (!moduleDeclarationMatcher.find()) {
            throw new IllegalStateException("No module declaration found in " + file);
        }
        int moduleStart = moduleDeclarationMatcher.start();
        String prelude = existing.substring(0, moduleStart);
        String body = existing.substring(moduleStart);
        String updatedPrelude = updateJavadoc(prelude, entries);
        String updated = updatedPrelude + body;
        if (!updated.equals(existing)) {
            Files.writeString(file, updated);
        }
    }

    static SequencedMap<String, String> collectEntries(SequencedMap<String, Inventory.Dependency> closure,
                                                       Set<String> internal,
                                                       HashDigestFunction hashFunction) throws IOException {
        Set<Path> hashedElsewhere = new HashSet<>();
        for (Map.Entry<String, Inventory.Dependency> dependency : closure.entrySet()) {
            String coordinate = dependency.getKey().substring(dependency.getValue().group().length() + 1);
            if (!coordinate.startsWith("module/") && dependency.getValue().jar() != null) {
                hashedElsewhere.add(dependency.getValue().jar());
            }
        }
        SequencedMap<String, String> entries = new TreeMap<>();
        for (Map.Entry<String, Inventory.Dependency> dependency : closure.entrySet()) {
            String group = dependency.getValue().group();
            String key = dependency.getKey().substring(group.length() + 1);
            if (internal.contains(key)) {
                continue;
            }
            int lastSlash = key.lastIndexOf('/');
            int firstSlash = key.indexOf('/');
            if (lastSlash <= 0 || lastSlash == firstSlash) {
                continue;
            }
            String coordinate = key.substring(0, lastSlash);
            String version = key.substring(lastSlash + 1);
            boolean moduleRoot = group.equals("main") && coordinate.startsWith("module/");
            String mavenCoordinate = group.equals("main") && coordinate.startsWith("maven/")
                    ? coordinate.substring("maven/".length())
                    : null;
            boolean mavenShortcut = mavenCoordinate != null
                    && mavenCoordinate.indexOf('/') > 0
                    && mavenCoordinate.indexOf('/') == mavenCoordinate.lastIndexOf('/');
            // A module root in a Maven-resolved layout pins only the version: the root pom
            // it stands for is not hashed, and the jar it points at is hashed by its Maven entry.
            String checksum = moduleRoot
                    && dependency.getValue().jar() != null
                    && hashedElsewhere.contains(dependency.getValue().jar())
                    ? null
                    : computeChecksum(dependency.getValue(), hashFunction);
            String value = checksum == null ? version : version + " " + checksum;
            String entry;
            if (coordinate.startsWith("module/")) {
                String module = coordinate.substring("module/".length());
                // Module names cannot contain a dash, so a dash always introduces a classifier,
                // which pins as part of the version value to keep the pin keyed by module name.
                int dash = module.indexOf('-');
                if (dash >= 0) {
                    value = ":" + module.substring(dash + 1) + ":" + value;
                    module = module.substring(0, dash);
                }
                entry = group.equals("main") ? module : group + "/module/" + module;
            } else {
                entry = mavenShortcut ? mavenCoordinate : group + "/" + coordinate;
            }
            entries.putIfAbsent(entry, value);
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

    private static String updateJavadoc(String prelude, SequencedMap<String, String> entries) {
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
        // Keys with a platform-guarded line are preserved verbatim, guarded and fallback
        // lines alike: the local resolution only reflects the variant this machine selected.
        Set<String> guarded = new HashSet<>();
        for (String line : lines) {
            Matcher matcher = PIN_TAG.matcher(line);
            if (matcher.matches() && matcher.group(2) != null && matcher.group(2).trim().endsWith("]")) {
                guarded.add(expand(matcher.group(1)));
            }
        }
        int insertAt = -1;
        Iterator<String> it = lines.iterator();
        int index = 0;
        while (it.hasNext()) {
            String line = it.next();
            Matcher matcher = PIN_TAG.matcher(line);
            if (matcher.matches() && !guarded.contains(expand(matcher.group(1)))) {
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
            if (guarded.contains(expand(entry.getKey()))) {
                continue;
            }
            tags.add(" * @jenesis.pin " + entry.getKey() + " " + entry.getValue());
        }
        lines.addAll(insertAt, tags);
        return String.join("\n", lines);
    }

    private static String expand(String token) {
        int first = token.indexOf('/');
        if (first < 0) {
            return "main/module/" + token;
        }
        return token.indexOf('/', first + 1) < 0 ? "main/maven/" + token : token;
    }

    private static String renderJavadoc(SequencedMap<String, String> entries) {
        StringBuilder sb = new StringBuilder("/**\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append(" * @jenesis.pin ").append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }
        sb.append(" */");
        return sb.toString();
    }
}
