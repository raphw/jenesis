package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.project.DependencyScope;

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
                Path.of(SCOPES),
                Path.of(METADATA)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<Path> folders = arguments.values().stream().map(BuildStepArgument::folder).toList();
        SequencedProperties requires = SequencedProperties.ofFolders(folders, REQUIRES);
        SequencedProperties scopes = SequencedProperties.ofFolders(folders, SCOPES);
        SequencedProperties metadata = SequencedProperties.ofFolders(folders, METADATA);
        boolean scoped = !scopes.isEmpty();
        SequencedProperties compileRequires = new SequencedProperties();
        SequencedSet<String> runtimeRequires = new LinkedHashSet<>();
        for (String name : requires.stringPropertyNames()) {
            String scope = scopes.getProperty(name);
            if (scope == null) {
                compileRequires.setProperty(name, requires.getProperty(name));
            } else {
                List<String> parts = List.of(scope.split(","));
                if (parts.contains(DependencyScope.COMPILE.name())) {
                    compileRequires.setProperty(name, requires.getProperty(name));
                }
                if (parts.contains(DependencyScope.RUNTIME.name())) {
                    runtimeRequires.add(name);
                }
            }
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
        SequencedSet<String> allRequires = new LinkedHashSet<>(compileRequires.stringPropertyNames());
        if (scoped) {
            allRequires.addAll(runtimeRequires);
        }
        for (String name : allRequires) {
            int separator = name.indexOf('/');
            if (separator == -1 || !prefixes.contains(name.substring(0, separator))) {
                continue;
            }
            MavenDependencyKey.Versioned parsed = MavenDependencyKey.parse(name.substring(separator + 1));
            MavenDependencyScope scope;
            if (!scoped) {
                scope = MavenDependencyScope.COMPILE;
            } else {
                boolean inCompile = compileRequires.containsKey(name);
                boolean inRuntime = runtimeRequires.contains(name);
                if (inCompile && inRuntime) {
                    scope = MavenDependencyScope.COMPILE;
                } else if (inCompile) {
                    scope = MavenDependencyScope.PROVIDED;
                } else {
                    scope = MavenDependencyScope.RUNTIME;
                }
            }
            deps.putIfAbsent(parsed.key(), new MavenDependencyValue(
                    parsed.version(),
                    scope,
                    null,
                    null,
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
        List<MavenPomEmitter.Metadata.License> licenses = List.of();
        String licenseName = metadata.getProperty("license.name");
        String licenseUrl = metadata.getProperty("license.url");
        if (licenseName != null || licenseUrl != null) {
            licenses = List.of(new MavenPomEmitter.Metadata.License(licenseName, licenseUrl));
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
