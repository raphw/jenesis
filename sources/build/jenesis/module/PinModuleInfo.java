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
    private final Path moduleInfoFile;

    public PinModuleInfo(String prefix, Path moduleInfoFile) {
        this.prefix = prefix;
        this.moduleInfoFile = moduleInfoFile;
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
        String existing = Files.readString(moduleInfoFile);
        Matcher moduleMatch = MODULE_DECLARATION.matcher(existing);
        if (!moduleMatch.find()) {
            throw new IllegalStateException("No module declaration found in " + moduleInfoFile);
        }
        int moduleStart = moduleMatch.start();
        String prelude = existing.substring(0, moduleStart);
        String body = existing.substring(moduleStart);
        String updatedPrelude = updateJavadoc(prelude, entries);
        String updated = updatedPrelude + body;
        if (!updated.equals(existing)) {
            Files.writeString(moduleInfoFile, updated);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
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
            insertAt = lines.size() - 1;
            if (insertAt < 1) {
                insertAt = 1;
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
