package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;

public class PinPom implements BuildStep {

    private static final Pattern DEPENDENCY_MANAGEMENT = Pattern.compile(
            "(?s)([ \\t]*)<dependencyManagement>.*?</dependencyManagement>\\s*\\n");
    private static final Pattern DEPENDENCIES_OPEN = Pattern.compile("([ \\t]*)<dependencies>");
    private static final Pattern PROJECT_CLOSE = Pattern.compile("\\n([ \\t]*)</project>");
    private static final Pattern CHECKSUM_COMMENT = Pattern.compile("[ \\t]*<!--Checksum/[^>]*-->\\s*\\n");
    private static final Pattern INDENT = Pattern.compile("\\n([ \\t]+)<");

    private final String prefix;
    private final List<Path> pomFiles;
    private final transient HashDigestFunction hashFunction;

    public PinPom(String prefix, Path pomFile, HashDigestFunction hashFunction) {
        this(prefix, List.of(pomFile), hashFunction);
    }

    public PinPom(String prefix, List<Path> pomFiles, HashDigestFunction hashFunction) {
        this.prefix = prefix;
        this.pomFiles = List.copyOf(pomFiles);
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
        SequencedMap<String, String> entries = collectEntries(arguments, prefix, hashFunction);
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
        String block = entries.isEmpty() ? "" : renderBlock(entries, indent);
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
        updated = stripDirectDependencyChecksums(updated);
        if (!updated.equals(existing)) {
            Files.writeString(pomFile, updated);
        }
    }

    static SequencedMap<String, String> collectEntries(SequencedMap<String, BuildStepArgument> arguments,
                                                       String prefix,
                                                       HashDigestFunction hashFunction) throws IOException {
        Set<String> internal = collectInternal(arguments);
        SequencedMap<String, String> entries = new TreeMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path versionsFile = argument.folder().resolve(VERSIONS);
            if (Files.exists(versionsFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(versionsFile);
                for (String key : properties.stringPropertyNames()) {
                    if (internal.contains(key)) {
                        continue;
                    }
                    int slash = key.indexOf('/');
                    if (slash < 0 || !prefix.equals(key.substring(0, slash))) {
                        continue;
                    }
                    String value = properties.getProperty(key);
                    int space = value.indexOf(' ');
                    String version = space < 0 ? value : value.substring(0, space);
                    String existing = space < 0 ? null : value.substring(space + 1).trim();
                    String computed = computeChecksum(arguments.values(), key + "/" + version, hashFunction);
                    String checksum = computed != null ? computed : existing;
                    entries.putIfAbsent(key.substring(slash + 1), checksum == null ? version : version + " " + checksum);
                }
            }
            Path requiresFile = argument.folder().resolve(REQUIRES);
            if (Files.exists(requiresFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(requiresFile);
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
                    String existing = properties.getProperty(key);
                    String computed = computeChecksum(arguments.values(), key, hashFunction);
                    String checksum = computed != null ? computed : (existing.isEmpty() ? null : existing);
                    entries.putIfAbsent(bomKey, checksum == null ? version : version + " " + checksum);
                }
            }
        }
        return entries;
    }

    private static String computeChecksum(Iterable<BuildStepArgument> arguments,
                                          String coordinate,
                                          HashDigestFunction hashFunction) throws IOException {
        String filename = coordinate.replace('/', '-') + ".jar";
        for (BuildStepArgument argument : arguments) {
            Path jar = argument.folder().resolve(BuildStep.DEPENDENCIES).resolve(filename);
            if (Files.isRegularFile(jar)) {
                return hashFunction.encodedHash(jar);
            }
        }
        return null;
    }

    static Set<String> collectInternal(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        Set<String> internal = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path identityFile = argument.folder().resolve(IDENTITY);
            if (!Files.exists(identityFile)) {
                continue;
            }
            SequencedProperties properties = SequencedProperties.ofFiles(identityFile);
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
