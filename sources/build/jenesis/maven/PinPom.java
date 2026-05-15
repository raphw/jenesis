package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class PinPom implements BuildStep {

    private static final Pattern DEPENDENCY_MANAGEMENT = Pattern.compile(
            "(?s)([ \\t]*)<dependencyManagement>.*?</dependencyManagement>\\s*\\n");
    private static final Pattern DEPENDENCIES_OPEN = Pattern.compile("([ \\t]*)<dependencies>");
    private static final Pattern PROJECT_CLOSE = Pattern.compile("\\n([ \\t]*)</project>");
    private static final Pattern CHECKSUM_COMMENT = Pattern.compile("[ \\t]*<!--Checksum/[^>]*-->\\s*\\n");

    private final String prefix;
    private final Path pomFile;

    public PinPom(String prefix, Path pomFile) {
        this.prefix = prefix;
        this.pomFile = pomFile;
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
        SequencedMap<String, String> entries = new TreeMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path file = argument.folder().resolve(VERSIONS);
            if (!Files.exists(file)) {
                continue;
            }
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            }
            for (String key : properties.stringPropertyNames()) {
                int slash = key.indexOf('/');
                if (slash < 0 || !prefix.equals(key.substring(0, slash))) {
                    continue;
                }
                entries.putIfAbsent(key.substring(slash + 1), properties.getProperty(key));
            }
        }
        String existing = Files.readString(pomFile);
        Matcher dmMatch = DEPENDENCY_MANAGEMENT.matcher(existing);
        String indent = dmMatch.find() ? dmMatch.group(1) : detectIndent(existing);
        String block = entries.isEmpty() ? "" : renderBlock(entries, indent);
        String updated;
        if (dmMatch.find(0)) {
            updated = dmMatch.replaceFirst(Matcher.quoteReplacement(block));
        } else if (block.isEmpty()) {
            updated = existing;
        } else {
            Matcher depsMatch = DEPENDENCIES_OPEN.matcher(existing);
            if (depsMatch.find()) {
                updated = existing.substring(0, depsMatch.start()) + block + existing.substring(depsMatch.start());
            } else {
                Matcher closeMatch = PROJECT_CLOSE.matcher(existing);
                if (!closeMatch.find()) {
                    throw new IllegalStateException("No </project> tag in " + pomFile);
                }
                updated = existing.substring(0, closeMatch.start() + 1) + block + existing.substring(closeMatch.start() + 1);
            }
        }
        updated = stripDirectDependencyChecksums(updated);
        if (!updated.equals(existing)) {
            Files.writeString(pomFile, updated);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String stripDirectDependencyChecksums(String content) {
        Matcher dmMatch = DEPENDENCY_MANAGEMENT.matcher(content);
        int dmStart = -1, dmEnd = -1;
        if (dmMatch.find()) {
            dmStart = dmMatch.start();
            dmEnd = dmMatch.end();
        }
        Matcher checksumMatch = CHECKSUM_COMMENT.matcher(content);
        StringBuilder result = new StringBuilder();
        int prev = 0;
        while (checksumMatch.find()) {
            if (checksumMatch.start() >= dmStart && checksumMatch.end() <= dmEnd) {
                continue;
            }
            result.append(content, prev, checksumMatch.start());
            prev = checksumMatch.end();
        }
        result.append(content, prev, content.length());
        return result.toString();
    }

    private static String detectIndent(String existing) {
        Matcher match = Pattern.compile("\\n([ \\t]+)<").matcher(existing);
        return match.find() ? match.group(1) : "    ";
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
            String dep = indent + indent + indent;
            sb.append(dep).append("<dependency>\n");
            sb.append(dep).append(indent).append("<groupId>").append(groupId).append("</groupId>\n");
            sb.append(dep).append(indent).append("<artifactId>").append(artifactId).append("</artifactId>\n");
            sb.append(dep).append(indent).append("<version>").append(version).append("</version>\n");
            if (type != null && !"jar".equals(type)) {
                sb.append(dep).append(indent).append("<type>").append(type).append("</type>\n");
            }
            if (classifier != null) {
                sb.append(dep).append(indent).append("<classifier>").append(classifier).append("</classifier>\n");
            }
            if (checksum != null) {
                sb.append(dep).append(indent).append("<!--Checksum/").append(checksum).append("-->\n");
            }
            sb.append(dep).append("</dependency>\n");
        }
        sb.append(indent).append(indent).append("</dependencies>\n");
        sb.append(indent).append("</dependencyManagement>\n");
        return sb.toString();
    }
}
