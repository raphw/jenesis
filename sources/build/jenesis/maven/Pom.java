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

    private final Function<String, String> resolver;
    private final Map<String, String> shared;
    private final transient MavenPomEmitter emitter = new MavenPomEmitter();

    public Pom() {
        this(Map.of());
    }

    public Pom(Map<String, String> shared) {
        this.resolver = (Function<String, String> & Serializable) (coordinate -> {
            int separator = coordinate.indexOf('/');
            if (separator == -1 || !"module".equals(coordinate.substring(0, separator))) {
                return coordinate;
            }
            String name = coordinate.substring(separator + 1);
            String[] elements = name.split("\\.");
            if (elements.length < 2) {
                return coordinate;
            }
            String groupId = elements[0] + "." + elements[1];
            return "maven/" + groupId + "/" + name + "/0-SNAPSHOT";
        });
        this.shared = Map.copyOf(shared);
    }

    public <F extends Function<String, String> & Serializable> Pom(F resolver) {
        this(resolver, Map.of());
    }

    public <F extends Function<String, String> & Serializable> Pom(F resolver, Map<String, String> shared) {
        this.resolver = resolver;
        this.shared = Map.copyOf(shared);
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<Path> folders = arguments.values().stream().map(BuildStepArgument::folder).toList();
        Properties coordinates = SequencedProperties.ofFolders(folders, IDENTITY);
        Properties requires = SequencedProperties.ofFolders(folders, REQUIRES);
        Properties scopes = SequencedProperties.ofFolders(folders, SCOPES);
        Properties module = SequencedProperties.ofFolders(folders, MODULE);
        Properties metadata = SequencedProperties.ofFolders(folders, PROJECT);
        boolean scoped = !scopes.isEmpty();
        Properties compileRequires = new SequencedProperties();
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
        String prefix = null;
        MavenDependencyKey.Versioned self = null;
        for (String coordinate : coordinates.stringPropertyNames()) {
            if (!coordinates.getProperty(coordinate).isEmpty()) {
                continue;
            }
            String resolved = resolver.apply(coordinate);
            int separator = resolved.indexOf('/');
            if (separator == -1) {
                continue;
            }
            MavenDependencyKey.Versioned parsed;
            try {
                parsed = MavenDependencyKey.parse(resolved.substring(separator + 1));
            } catch (IllegalArgumentException _) {
                continue;
            }
            if ("pom".equals(parsed.key().type())) {
                continue;
            }
            prefix = resolved.substring(0, separator);
            self = parsed;
            break;
        }
        if (self == null) {
            throw new IllegalStateException(
                    "No own Maven coordinate (with empty value) found in coordinates.properties");
        }
        String targetModule = metadata.getProperty("module");
        boolean test = module.getProperty("tests") != null;
        if (targetModule != null && !targetModule.equals(self.key().artifactId()) && !test) {
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
        SequencedMap<MavenDependencyKey, MavenDependencyValue> deps = new LinkedHashMap<>();
        SequencedSet<String> allRequires = new LinkedHashSet<>(compileRequires.stringPropertyNames());
        if (scoped) {
            allRequires.addAll(runtimeRequires);
        }
        for (String name : allRequires) {
            int separator = name.indexOf('/');
            if (separator == -1 || !prefix.equals(name.substring(0, separator))) {
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
        String metadataVersion = metadata.getProperty("version");
        String version = metadataVersion != null ? metadataVersion : self.version();
        MavenPomEmitter.Metadata parsed = null;
        if (!metadata.isEmpty()) {
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
            parsed = new MavenPomEmitter.Metadata(
                    metadata.getProperty("name"),
                    metadata.getProperty("description"),
                    metadata.getProperty("url"),
                    licenses,
                    developers,
                    scm);
        }
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(POM))) {
            emitter.emit(
                    self.key().groupId(),
                    self.key().artifactId(),
                    version,
                    "jar".equals(self.key().type()) ? null : self.key().type(),
                    deps,
                    parsed).accept(writer);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

}
