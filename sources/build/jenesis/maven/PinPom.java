package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.Platform;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;

public class PinPom implements BuildStep {

    private static final Pattern DEPENDENCY_MANAGEMENT = Pattern.compile(
            "(?s)([ \\t]*)<dependencyManagement>.*?</dependencyManagement>\\s*\\n");
    private static final Pattern DEPENDENCIES_OPEN = Pattern.compile("([ \\t]*)<dependencies>");
    private static final Pattern PROJECT_CLOSE = Pattern.compile("\\n([ \\t]*)</project>");
    private static final Pattern CHECKSUM_COMMENT = Pattern.compile("[ \\t]*<!--\\s*Checksum/[^>]*-->\\s*\\n");
    private static final Pattern INDENT = Pattern.compile("\\n([ \\t]+)<");
    private static final Pattern PIN_COMMENT = Pattern.compile("(?s)([ \\t]*)<!--\\s*jenesis\\.pin\\b(.*?)-->\\s*\\n");

    private final String prefix;
    private final String path;
    private final List<Path> pomFiles;
    private final transient HashDigestFunction hashFunction;
    private final Platform platform;

    public PinPom(String prefix, String path, List<Path> pomFiles, HashDigestFunction hashFunction) {
        this(prefix, path, pomFiles, hashFunction, new Platform());
    }

    private PinPom(String prefix,
                   String path,
                   List<Path> pomFiles,
                   HashDigestFunction hashFunction,
                   Platform platform) {
        this.prefix = prefix;
        this.path = path;
        this.pomFiles = List.copyOf(pomFiles);
        this.hashFunction = hashFunction;
        this.platform = platform;
    }

    public PinPom platform(Platform platform) {
        return new PinPom(prefix, path, pomFiles, hashFunction, platform);
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
        for (Path pomFile : pomFiles) {
            updatePom(pomFile, entries);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private void updatePom(Path pomFile, SequencedMap<String, String> entries) throws IOException {
        String existing = Files.readString(pomFile);
        Matcher dependencyManagementMatcher = DEPENDENCY_MANAGEMENT.matcher(existing);
        String indent;
        if (dependencyManagementMatcher.find()) {
            indent = dependencyManagementMatcher.group(1);
        } else {
            Matcher indentMatcher = INDENT.matcher(existing);
            indent = indentMatcher.find() ? indentMatcher.group(1) : "    ";
        }
        SequencedMap<String, String> managed = new TreeMap<>();
        SequencedMap<String, String> qualified = new TreeMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            int first = key.indexOf('/');
            int second = key.indexOf('/', first + 1);
            String group = key.substring(0, first);
            String repository = second < 0 ? "" : key.substring(first + 1, second);
            if (repository.equals("maven") && group.equals("main")) {
                managed.putIfAbsent(key.substring(second + 1), entry.getValue());
            } else {
                qualified.put(key, entry.getValue());
            }
        }
        Matcher pinMatcher = PIN_COMMENT.matcher(existing);
        List<String> preserved = pinMatcher.find()
                ? preserveGuarded(pinMatcher.group(2), qualified, managed)
                : List.of();
        String block = managed.isEmpty() ? "" : renderBlock(managed, indent);
        String updated;
        if (dependencyManagementMatcher.find(0)) {
            updated = dependencyManagementMatcher.replaceFirst(Matcher.quoteReplacement(block));
        } else if (block.isEmpty()) {
            updated = existing;
        } else {
            Matcher dependenciesMatcher = DEPENDENCIES_OPEN.matcher(existing);
            if (dependenciesMatcher.find()) {
                updated = existing.substring(0, dependenciesMatcher.start()) + block + existing.substring(dependenciesMatcher.start());
            } else {
                Matcher projectCloseMatcher = PROJECT_CLOSE.matcher(existing);
                if (!projectCloseMatcher.find()) {
                    throw new IllegalStateException("No </project> tag in " + pomFile);
                }
                updated = existing.substring(0, projectCloseMatcher.start() + 1) + block + existing.substring(projectCloseMatcher.start() + 1);
            }
        }
        Matcher requiresMatcher = PIN_COMMENT.matcher(updated);
        String requires = qualified.isEmpty() && preserved.isEmpty() ? "" : renderRequires(qualified, preserved, indent);
        if (requiresMatcher.find()) {
            updated = requiresMatcher.replaceFirst(Matcher.quoteReplacement(requires));
        } else if (!requires.isEmpty()) {
            Matcher projectCloseMatcher = PROJECT_CLOSE.matcher(updated);
            if (!projectCloseMatcher.find()) {
                throw new IllegalStateException("No </project> tag in " + pomFile);
            }
            updated = updated.substring(0, projectCloseMatcher.start() + 1) + requires + updated.substring(projectCloseMatcher.start() + 1);
        }
        updated = stripDirectDependencyChecksums(updated);
        if (!updated.equals(existing)) {
            Files.writeString(pomFile, updated);
        }
    }

    static SequencedMap<String, String> collectEntries(SequencedMap<String, Inventory.Dependency> closure,
                                                       Set<String> internal,
                                                       HashDigestFunction hashFunction) throws IOException {
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
            String checksum = computeChecksum(dependency.getValue(), hashFunction);
            String value = checksum == null ? version : version + " " + checksum;
            entries.putIfAbsent(group + "/" + coordinate, value);
        }
        return entries;
    }

    private List<String> preserveGuarded(String block,
                                         SequencedMap<String, String> qualified,
                                         SequencedMap<String, String> managed) {
        record Pin(String token, String value, String guard) {
        }
        List<Pin> pins = new ArrayList<>();
        Set<String> guarded = new LinkedHashSet<>();
        for (String line : block.replace("&#45;", "-").split("\n")) {
            String trimmed = line.trim().replaceAll("\\s+", " ");
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space < 1) {
                continue;
            }
            String token = trimmed.substring(0, space);
            String value = trimmed.substring(space + 1).trim();
            String guard = null;
            if (value.endsWith("]")) {
                int bracket = value.lastIndexOf('[');
                if (bracket >= 0) {
                    guard = value.substring(bracket + 1, value.length() - 1);
                    value = value.substring(0, bracket).trim();
                }
            }
            pins.add(new Pin(token, value, guard));
            if (guard != null) {
                guarded.add(expand(token));
            }
        }
        if (guarded.isEmpty()) {
            return List.of();
        }
        // A coordinate with platform guards keeps every line in place; only the line whose
        // guard matched the local platform (or the unguarded fallback) is refreshed from the
        // resolved closure, since this resolution only reflects the local variant. Lines are
        // matched by their expanded coordinate, so short and full forms refer to the same key.
        for (String key : guarded) {
            String resolved = key.startsWith("main/maven/")
                    ? managed.remove(key.substring("main/maven/".length()))
                    : qualified.remove(key);
            Integer fallback = null, matched = null;
            int specificity = 0;
            boolean ambiguous = false;
            for (int index = 0; index < pins.size(); index++) {
                Pin pin = pins.get(index);
                if (!expand(pin.token()).equals(key)) {
                    continue;
                }
                if (pin.guard() == null) {
                    fallback = index;
                    continue;
                }
                Platform guard = Platform.of(pin.guard());
                if (!platform.matches(guard)) {
                    continue;
                }
                if (guard.tokens().size() > specificity) {
                    matched = index;
                    specificity = guard.tokens().size();
                    ambiguous = false;
                } else if (guard.tokens().size() == specificity) {
                    ambiguous = true;
                }
            }
            Integer winner = matched != null ? matched : fallback;
            if (resolved != null && winner != null && !ambiguous) {
                Pin pin = pins.get(winner);
                pins.set(winner, new Pin(pin.token(), resolved, pin.guard()));
            }
        }
        List<String> preserved = new ArrayList<>();
        for (Pin pin : pins) {
            if (guarded.contains(expand(pin.token()))) {
                preserved.add(pin.token() + " " + pin.value() + (pin.guard() == null ? "" : " [" + pin.guard() + "]"));
            }
        }
        return preserved;
    }

    private static String expand(String token) {
        int first = token.indexOf('/');
        if (first < 0) {
            return "main/module/" + token;
        }
        return token.indexOf('/', first + 1) < 0 ? "main/maven/" + token : token;
    }

    private static String renderRequires(SequencedMap<String, String> qualified, List<String> preserved, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("<!--jenesis.pin\n");
        for (String line : preserved) {
            sb.append(indent).append(line.replace("--", "&#45;&#45;")).append("\n");
        }
        for (Map.Entry<String, String> entry : qualified.entrySet()) {
            sb.append(indent).append((entry.getKey() + " " + entry.getValue()).replace("--", "&#45;&#45;")).append("\n");
        }
        sb.append(indent).append("-->\n");
        return sb.toString();
    }

    private static String computeChecksum(Inventory.Dependency dependency,
                                          HashDigestFunction hashFunction) throws IOException {
        if (dependency.jar() != null && Files.isRegularFile(dependency.jar())) {
            return hashFunction.encodedHash(dependency.jar());
        }
        return dependency.checksum().isEmpty() ? null : dependency.checksum();
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

    private static String stripDirectDependencyChecksums(String content) {
        Matcher dependencyManagementMatcher = DEPENDENCY_MANAGEMENT.matcher(content);
        int dependencyManagementStart = -1, dependencyManagementEnd = -1;
        if (dependencyManagementMatcher.find()) {
            dependencyManagementStart = dependencyManagementMatcher.start();
            dependencyManagementEnd = dependencyManagementMatcher.end();
        }
        Matcher checksumMatcher = CHECKSUM_COMMENT.matcher(content);
        StringBuilder result = new StringBuilder();
        int previous = 0;
        while (checksumMatcher.find()) {
            if (checksumMatcher.start() >= dependencyManagementStart && checksumMatcher.end() <= dependencyManagementEnd) {
                continue;
            }
            result.append(content, previous, checksumMatcher.start());
            previous = checksumMatcher.end();
        }
        result.append(content, previous, content.length());
        return result.toString();
    }

    private static String renderBlock(SequencedMap<String, String> entries, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("<dependencyManagement>\n");
        sb.append(indent).append(indent).append("<dependencies>\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String[] elements = entry.getKey().split("/");
            String groupId, artifactId, type, classifier;
            switch (elements.length) {
                case 2 -> { groupId = elements[0]; artifactId = elements[1]; type = null; classifier = null; }
                case 3 -> { groupId = elements[0]; artifactId = elements[1]; type = elements[2]; classifier = null; }
                case 4 -> { groupId = elements[0]; artifactId = elements[1]; type = elements[2]; classifier = elements[3]; }
                default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + entry.getKey());
            }
            String value = entry.getValue();
            int space = value.indexOf(' ');
            String version = space < 0 ? value : value.substring(0, space);
            String checksum = space < 0 ? null : value.substring(space + 1).trim();
            String prefix = indent + indent + indent;
            sb.append(prefix).append("<dependency>\n");
            sb.append(prefix).append(indent).append("<groupId>").append(groupId).append("</groupId>\n");
            sb.append(prefix).append(indent).append("<artifactId>").append(artifactId).append("</artifactId>\n");
            sb.append(prefix).append(indent).append("<version>").append(version).append("</version>\n");
            if (type != null && !"jar".equals(type)) {
                sb.append(prefix).append(indent).append("<type>").append(type).append("</type>\n");
            }
            if (classifier != null) {
                sb.append(prefix).append(indent).append("<classifier>").append(classifier).append("</classifier>\n");
            }
            if (checksum != null) {
                sb.append(prefix).append(indent).append("<!--Checksum/").append(checksum).append("-->\n");
            }
            sb.append(prefix).append("</dependency>\n");
        }
        sb.append(indent).append(indent).append("</dependencies>\n");
        sb.append(indent).append("</dependencyManagement>\n");
        return sb.toString();
    }
}
