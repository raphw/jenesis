package build.jenesis.module;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.Platform;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;

public class PinModuleInfo implements BuildStep {

    private static final Pattern MODULE_DECLARATION = Pattern.compile("(?m)^(open\\s+)?module\\s+");
    private static final Pattern JAVADOC_END = Pattern.compile("\\*/\\s*$");
    private static final Pattern PIN_TAG = Pattern.compile("^\\s*\\*\\s*@jenesis\\.pin\\s+(\\S+)(\\s+.*)?$");
    private static final Pattern BOM_TAG = Pattern.compile("^\\s*\\*\\s*@jenesis\\.bom\\s+(\\S+)(\\s+.*)?$");

    private final String prefix;
    private final String path;
    private final List<Path> moduleInfoFiles;
    private final transient HashDigestFunction hashFunction;
    private final Platform platform;
    private final boolean checksum;
    private final boolean flatten;

    public PinModuleInfo(String prefix, String path, List<Path> moduleInfoFiles, HashDigestFunction hashFunction) {
        this(prefix, path, moduleInfoFiles, hashFunction, new Platform(), checksumFromProperty(), flattenFromProperty());
    }

    private PinModuleInfo(String prefix,
                          String path,
                          List<Path> moduleInfoFiles,
                          HashDigestFunction hashFunction,
                          Platform platform,
                          boolean checksum,
                          boolean flatten) {
        this.prefix = prefix;
        this.path = path;
        this.moduleInfoFiles = List.copyOf(moduleInfoFiles);
        this.hashFunction = hashFunction;
        this.platform = platform;
        this.checksum = checksum;
        this.flatten = flatten;
    }

    public PinModuleInfo platform(Platform platform) {
        return new PinModuleInfo(prefix, path, moduleInfoFiles, hashFunction, platform, checksum, flatten);
    }

    public PinModuleInfo checksum(boolean checksum) {
        return new PinModuleInfo(prefix, path, moduleInfoFiles, hashFunction, platform, checksum, flatten);
    }

    public PinModuleInfo flatten(boolean flatten) {
        return new PinModuleInfo(prefix, path, moduleInfoFiles, hashFunction, platform, checksum, flatten);
    }

    private static boolean checksumFromProperty() {
        String value = System.getProperty("jenesis.pin.checksum");
        if (value == null || value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("Unknown pin checksum mode: " + value + " (expected true or false)");
    }

    private static boolean flattenFromProperty() {
        String value = System.getProperty("jenesis.pin.bom");
        if (value == null || value.equals("keep")) {
            return false;
        }
        if (value.equals("flatten")) {
            return true;
        }
        throw new IllegalArgumentException("Unknown pin BOM mode: " + value + " (expected keep or flatten)");
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
        SequencedMap<String, String> entries = collectEntries(closure, internal, checksum ? hashFunction : null);
        Set<String> covered = new HashSet<>();
        SequencedMap<String, String> references = new LinkedHashMap<>();
        if (!flatten) {
            SequencedMap<String, String> managed = Inventory.bomEntries(arguments.values(), path);
            Iterator<Map.Entry<String, String>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> entry = it.next();
                String expanded = expand(entry.getKey());
                String supplied = managed.get(expanded);
                if (supplied != null && covers(supplied, entry.getValue())) {
                    covered.add(expanded);
                    it.remove();
                }
            }
            for (Map.Entry<String, Path> reference : Inventory.bomReferences(arguments.values(), path).entrySet()) {
                int lastSlash = reference.getKey().lastIndexOf('/');
                String version = reference.getKey().substring(lastSlash + 1);
                references.put(reference.getKey().substring(0, lastSlash), checksum
                        ? version + " " + hashFunction.encodedHash(reference.getValue())
                        : version);
            }
        }
        for (Path file : moduleInfoFiles) {
            updateModuleInfo(file, entries, covered, references, flatten, platform);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static boolean covers(String supplied, String pin) {
        int suppliedSpace = supplied.indexOf(' ');
        String suppliedVersion = suppliedSpace < 0 ? supplied : supplied.substring(0, suppliedSpace);
        String suppliedChecksum = suppliedSpace < 0 ? null : supplied.substring(suppliedSpace + 1).trim();
        int pinSpace = pin.indexOf(' ');
        String pinVersion = pinSpace < 0 ? pin : pin.substring(0, pinSpace);
        String pinChecksum = pinSpace < 0 ? null : pin.substring(pinSpace + 1).trim();
        return suppliedVersion.equals(pinVersion) && (pinChecksum == null || pinChecksum.equals(suppliedChecksum));
    }

    private static String computeChecksum(Inventory.Dependency dependency,
                                          HashDigestFunction hashFunction) throws IOException {
        if (dependency.jar() != null && Files.isRegularFile(dependency.jar())) {
            return hashFunction.encodedHash(dependency.jar());
        }
        return dependency.checksum().isEmpty() ? null : dependency.checksum();
    }

    private static void updateModuleInfo(Path file,
                                         SequencedMap<String, String> entries,
                                         Set<String> covered,
                                         SequencedMap<String, String> references,
                                         boolean flatten,
                                         Platform platform) throws IOException {
        String existing = Files.readString(file);
        Matcher moduleDeclarationMatcher = MODULE_DECLARATION.matcher(existing);
        if (!moduleDeclarationMatcher.find()) {
            throw new IllegalStateException("No module declaration found in " + file);
        }
        int moduleStart = moduleDeclarationMatcher.start();
        String prelude = existing.substring(0, moduleStart);
        String body = existing.substring(moduleStart);
        String updatedPrelude = updateJavadoc(prelude, entries, covered, references, flatten, platform);
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
            String checksum = hashFunction == null
                    || (moduleRoot
                    && dependency.getValue().jar() != null
                    && hashedElsewhere.contains(dependency.getValue().jar()))
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

    private static String updateJavadoc(String prelude,
                                        SequencedMap<String, String> entries,
                                        Set<String> covered,
                                        SequencedMap<String, String> references,
                                        boolean flatten,
                                        Platform platform) {
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
        String rewritten = rewriteJavadoc(javadoc, entries, covered, references, flatten, platform);
        return before + rewritten + after;
    }

    private record PinLine(int index, String token, String guard) {
    }

    private static String rewriteJavadoc(String javadoc,
                                         SequencedMap<String, String> entries,
                                         Set<String> covered,
                                         SequencedMap<String, String> references,
                                         boolean flatten,
                                         Platform platform) {
        List<String> lines = new ArrayList<>(List.of(javadoc.split("\\n", -1)));
        rewriteBoms(lines, references, flatten, platform);
        SequencedMap<String, List<PinLine>> guarded = new LinkedHashMap<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            Matcher matcher = PIN_TAG.matcher(lines.get(lineIndex));
            if (matcher.matches() && matcher.group(2) != null && matcher.group(2).trim().endsWith("]")) {
                guarded.computeIfAbsent(expand(matcher.group(1)), _ -> new ArrayList<>());
            }
        }
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            Matcher matcher = PIN_TAG.matcher(lines.get(lineIndex));
            if (!matcher.matches()) {
                continue;
            }
            String key = expand(matcher.group(1));
            List<PinLine> pins = guarded.get(key);
            if (pins == null) {
                continue;
            }
            String rest = matcher.group(2) == null ? "" : matcher.group(2).trim();
            String guard = null;
            if (rest.endsWith("]")) {
                int bracket = rest.lastIndexOf('[');
                if (bracket >= 0) {
                    guard = rest.substring(bracket + 1, rest.length() - 1);
                }
            }
            pins.add(new PinLine(lineIndex, matcher.group(1), guard));
        }
        // A key with platform guards keeps every line in place; only the line whose guard
        // matched the local platform (or the unguarded fallback) is refreshed from the
        // resolved closure, since this resolution only reflects the local variant.
        SequencedMap<String, String> expanded = new LinkedHashMap<>();
        entries.forEach((key, value) -> expanded.put(expand(key), key + " " + value));
        for (Map.Entry<String, List<PinLine>> entry : guarded.entrySet()) {
            String resolved = expanded.get(entry.getKey());
            if (resolved == null) {
                continue;
            }
            PinLine fallback = null, matched = null;
            int specificity = 0;
            boolean ambiguous = false;
            for (PinLine pin : entry.getValue()) {
                if (pin.guard() == null) {
                    fallback = pin;
                    continue;
                }
                Platform guard = Platform.of(pin.guard());
                if (!platform.matches(guard)) {
                    continue;
                }
                if (guard.tokens().size() > specificity) {
                    matched = pin;
                    specificity = guard.tokens().size();
                    ambiguous = false;
                } else if (guard.tokens().size() == specificity) {
                    ambiguous = true;
                }
            }
            if (ambiguous) {
                continue;
            }
            PinLine winner = matched != null ? matched : fallback;
            if (winner != null) {
                lines.set(winner.index(), " * @jenesis.pin "
                        + resolved
                        + (winner.guard() == null ? "" : " [" + winner.guard() + "]"));
            }
        }
        Set<String> regenerated = new HashSet<>();
        for (String key : entries.keySet()) {
            regenerated.add(expand(key));
        }
        // A coordinate covered by a BOM is regenerated as no pin at all: its stale unguarded
        // line is dropped rather than preserved, since the BOM entry now supplies the value.
        regenerated.addAll(covered);
        // An unguarded line whose coordinate is not in the resolved closure is a manual override
        // (for example a transitively required module name) and is preserved as-is; only lines the
        // closure regenerates are rewritten, so a repin never discards a hand-set version.
        SequencedMap<String, String> merged = new TreeMap<>();
        int insertAt = -1;
        Iterator<String> it = lines.iterator();
        int index = 0;
        while (it.hasNext()) {
            String line = it.next();
            Matcher matcher = PIN_TAG.matcher(line);
            if (matcher.matches() && !guarded.containsKey(expand(matcher.group(1)))) {
                if (insertAt < 0) {
                    insertAt = index;
                }
                if (!regenerated.contains(expand(matcher.group(1)))) {
                    merged.putIfAbsent(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2).trim());
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
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (guarded.containsKey(expand(entry.getKey()))) {
                continue;
            }
            merged.put(entry.getKey(), entry.getValue());
        }
        List<String> tags = new ArrayList<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            tags.add(" * @jenesis.pin " + entry.getKey() + " " + entry.getValue());
        }
        lines.addAll(insertAt, tags);
        return String.join("\n", lines);
    }

    private static void rewriteBoms(List<String> lines,
                                    SequencedMap<String, String> references,
                                    boolean flatten,
                                    Platform platform) {
        if (flatten) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                Matcher matcher = BOM_TAG.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String rest = matcher.group(2) == null ? "" : matcher.group(2).trim();
                if (rest.endsWith("]")) {
                    throw new IllegalStateException("Cannot flatten platform-guarded BOM declaration: "
                            + line.trim());
                }
                it.remove();
            }
            return;
        }
        SequencedMap<String, List<PinLine>> declarations = new LinkedHashMap<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            Matcher matcher = BOM_TAG.matcher(lines.get(lineIndex));
            if (!matcher.matches()) {
                continue;
            }
            String token = matcher.group(1);
            String last = token.substring(token.lastIndexOf('/') + 1);
            if (last.startsWith("bom-") && last.endsWith(".properties")) {
                continue;
            }
            String rest = matcher.group(2) == null ? "" : matcher.group(2).trim();
            String guard = null;
            if (rest.endsWith("]")) {
                int bracket = rest.lastIndexOf('[');
                if (bracket >= 0) {
                    guard = rest.substring(bracket + 1, rest.length() - 1);
                }
            }
            declarations.computeIfAbsent(expand(token), _ -> new ArrayList<>())
                    .add(new PinLine(lineIndex, token, guard));
        }
        for (Map.Entry<String, List<PinLine>> entry : declarations.entrySet()) {
            String resolved = references.get(entry.getKey());
            if (resolved == null) {
                continue;
            }
            PinLine fallback = null, matched = null;
            int specificity = 0;
            boolean ambiguous = false;
            for (PinLine declaration : entry.getValue()) {
                if (declaration.guard() == null) {
                    fallback = declaration;
                    continue;
                }
                Platform guard = Platform.of(declaration.guard());
                if (!platform.matches(guard)) {
                    continue;
                }
                if (guard.tokens().size() > specificity) {
                    matched = declaration;
                    specificity = guard.tokens().size();
                    ambiguous = false;
                } else if (guard.tokens().size() == specificity) {
                    ambiguous = true;
                }
            }
            if (ambiguous) {
                continue;
            }
            PinLine winner = matched != null ? matched : fallback;
            if (winner != null) {
                lines.set(winner.index(), " * @jenesis.bom "
                        + winner.token()
                        + " "
                        + resolved
                        + (winner.guard() == null ? "" : " [" + winner.guard() + "]"));
            }
        }
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
