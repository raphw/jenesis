package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Pom implements BuildStep {

    public static final String POM = "pom.xml";

    private final Set<String> prefixes;
    private final Map<String, String> shared;
    private final transient MavenPomEmitter emitter = new MavenPomEmitter();

    public Pom() {
        this(Set.of("maven"), Map.of());
    }

    public Pom(Set<String> prefixes) {
        this(prefixes, Map.of());
    }

    public Pom(Map<String, String> shared) {
        this(Set.of("maven"), shared);
    }

    public Pom(Set<String> prefixes, Map<String, String> shared) {
        this.prefixes = Set.copyOf(prefixes);
        this.shared = Map.copyOf(shared);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(REQUIRES),
                Path.of(EXCLUSIONS),
                Path.of(METADATA)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<Path> folders = arguments.values().stream().map(BuildStepArgument::folder).toList();
        SequencedProperties requires = SequencedProperties.ofFolders(folders, REQUIRES);
        SequencedProperties exclusions = SequencedProperties.ofFolders(folders, EXCLUSIONS);
        SequencedProperties metadata = SequencedProperties.ofFolders(folders, METADATA);
        SequencedMap<String, SequencedSet<String>> coordinateScopes = new LinkedHashMap<>();
        for (String key : requires.stringPropertyNames()) {
            int slash = key.indexOf('/');
            coordinateScopes.computeIfAbsent(key.substring(slash + 1), _ -> new LinkedHashSet<>())
                    .add(key.substring(0, slash));
        }
        SequencedMap<String, String> coordinateExclusions = new LinkedHashMap<>();
        for (String key : exclusions.stringPropertyNames()) {
            coordinateExclusions.putIfAbsent(key.substring(key.indexOf('/') + 1), exclusions.getProperty(key));
        }
        shared.forEach(metadata::setProperty);
        String groupId = metadata.getProperty("project");
        if (groupId == null) {
            throw new IllegalStateException("Missing 'project' (groupId) in metadata.properties");
        }
        String artifactId = metadata.getProperty("artifact");
        if (artifactId == null) {
            throw new IllegalStateException(
                    "Missing 'artifact' (artifactId) in metadata.properties for " + groupId);
        }
        String version = metadata.getProperty("version");
        if (version == null) {
            throw new IllegalStateException(
                    "Missing 'version' in metadata.properties for " + groupId + ":" + artifactId);
        }
        SequencedMap<MavenDependencyKey, MavenDependencyValue> deps = new LinkedHashMap<>();
        for (Map.Entry<String, SequencedSet<String>> scopedEntry : coordinateScopes.entrySet()) {
            String name = scopedEntry.getKey();
            int separator = name.indexOf('/');
            if (separator == -1 || !prefixes.contains(name.substring(0, separator))) {
                continue;
            }
            boolean inCompile = scopedEntry.getValue().contains("compile");
            boolean inRuntime = scopedEntry.getValue().contains("runtime");
            if (!inCompile && !inRuntime) {
                continue;
            }
            MavenDependencyKey.Versioned parsed = MavenDependencyKey.parse(name.substring(separator + 1));
            MavenDependencyScope scope = inCompile && inRuntime
                    ? MavenDependencyScope.COMPILE
                    : inCompile ? MavenDependencyScope.PROVIDED : MavenDependencyScope.RUNTIME;
            List<MavenDependencyName> excludes = null;
            String exclusionList = coordinateExclusions.get(name);
            if (exclusionList != null && !exclusionList.isEmpty()) {
                excludes = new ArrayList<>();
                for (String entry : exclusionList.split(",")) {
                    int slash = entry.indexOf('/');
                    if (slash > 0) {
                        excludes.add(new MavenDependencyName(
                                entry.substring(0, slash),
                                entry.substring(slash + 1)));
                    }
                }
            }
            deps.putIfAbsent(parsed.key(), new MavenDependencyValue(
                    parsed.version(),
                    scope,
                    null,
                    excludes,
                    null));
        }
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(POM))) {
            emitter.emit(
                    groupId,
                    artifactId,
                    version,
                    deps,
                    parseMetadata(metadata)).accept(writer);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static MavenPomEmitter.Metadata parseMetadata(SequencedProperties metadata) {
        if (metadata.isEmpty()) {
            return null;
        }
        SequencedMap<String, String[]> licensesById = new LinkedHashMap<>();
        for (String key : metadata.stringPropertyNames()) {
            if (!key.startsWith("license.")) {
                continue;
            }
            String suffix = key.substring("license.".length());
            int dot = suffix.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String id = suffix.substring(0, dot);
            String attribute = suffix.substring(dot + 1);
            String[] entry = licensesById.computeIfAbsent(id, _ -> new String[2]);
            if ("name".equals(attribute)) {
                entry[0] = metadata.getProperty(key);
            } else if ("url".equals(attribute)) {
                entry[1] = metadata.getProperty(key);
            }
        }
        List<MavenPomEmitter.Metadata.License> licenses = new ArrayList<>();
        for (String[] entry : licensesById.values()) {
            licenses.add(new MavenPomEmitter.Metadata.License(entry[0], entry[1]));
        }
        SequencedMap<String, String[]> developersById = new LinkedHashMap<>();
        for (String key : metadata.stringPropertyNames()) {
            if (!key.startsWith("developer.")) {
                continue;
            }
            String suffix = key.substring("developer.".length());
            int dot = suffix.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String id = suffix.substring(0, dot);
            String attribute = suffix.substring(dot + 1);
            String[] entry = developersById.computeIfAbsent(id, _ -> new String[2]);
            if ("name".equals(attribute)) {
                entry[0] = metadata.getProperty(key);
            } else if ("email".equals(attribute)) {
                entry[1] = metadata.getProperty(key);
            }
        }
        List<MavenPomEmitter.Metadata.Developer> developers = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : developersById.entrySet()) {
            developers.add(new MavenPomEmitter.Metadata.Developer(
                    entry.getKey(),
                    entry.getValue()[0],
                    entry.getValue()[1]));
        }
        MavenPomEmitter.Metadata.Scm scm = null;
        String scmConnection = metadata.getProperty("scm.connection");
        String scmDeveloperConnection = metadata.getProperty("scm.developerConnection");
        String scmUrl = metadata.getProperty("scm.url");
        if (scmConnection != null || scmDeveloperConnection != null || scmUrl != null) {
            scm = new MavenPomEmitter.Metadata.Scm(
                    scmConnection,
                    scmDeveloperConnection,
                    scmUrl);
        }
        return new MavenPomEmitter.Metadata(
                metadata.getProperty("name"),
                metadata.getProperty("description"),
                metadata.getProperty("url"),
                licenses,
                developers,
                scm);
    }

}
