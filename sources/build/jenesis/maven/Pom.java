package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Pom implements BuildStep {

    public static final String POM = "pom.xml";

    private final Function<String, String> resolver;
    private final MavenPomEmitter.Metadata metadata;
    private final String buildVersion = System.getProperty("jenesis.buildVersion");
    private final transient MavenPomEmitter emitter = new MavenPomEmitter();

    public Pom() {
        this(defaultResolver(), null);
    }

    public Pom(MavenPomEmitter.Metadata metadata) {
        this(defaultResolver(), metadata);
    }

    public <F extends Function<String, String> & Serializable> Pom(F resolver) {
        this(resolver, null);
    }

    public <F extends Function<String, String> & Serializable> Pom(F resolver, MavenPomEmitter.Metadata metadata) {
        this.resolver = resolver;
        this.metadata = metadata;
    }

    @SuppressWarnings("unchecked")
    private static <F extends Function<String, String> & Serializable> F defaultResolver() {
        return (F) (Function<String, String> & Serializable) (coordinate -> {
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
            String artifactId = String.join(".", Arrays.asList(elements).subList(1, elements.length));
            return "maven/" + groupId + "/" + artifactId + "/0-SNAPSHOT";
        });
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Properties coordinates = new SequencedProperties();
        Properties dependencies = new SequencedProperties();
        for (BuildStepArgument argument : arguments.values()) {
            Path coordinatesFile = argument.folder().resolve(IDENTITY);
            if (Files.exists(coordinatesFile)) {
                try (Reader reader = Files.newBufferedReader(coordinatesFile)) {
                    coordinates.load(reader);
                }
            }
            Path dependenciesFile = argument.folder().resolve(REQUIRES);
            if (Files.exists(dependenciesFile)) {
                try (Reader reader = Files.newBufferedReader(dependenciesFile)) {
                    dependencies.load(reader);
                }
            }
        }
        String prefix = null;
        Parsed self = null;
        for (String coordinate : coordinates.stringPropertyNames()) {
            if (!coordinates.getProperty(coordinate).isEmpty()) {
                continue;
            }
            String resolved = resolver.apply(coordinate);
            int separator = resolved.indexOf('/');
            if (separator == -1) {
                continue;
            }
            String[] elements = resolved.substring(separator + 1).split("/");
            Parsed parsed = switch (elements.length) {
                case 3 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], "jar", null), elements[2]);
                case 4 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], elements[2], null), elements[3]);
                case 5 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], elements[2], elements[3]), elements[4]);
                default -> null;
            };
            if (parsed == null || "pom".equals(parsed.key().type())) {
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> deps = new LinkedHashMap<>();
        for (String name : dependencies.stringPropertyNames()) {
            int separator = name.indexOf('/');
            if (separator == -1 || !prefix.equals(name.substring(0, separator))) {
                continue;
            }
            String[] elements = name.substring(separator + 1).split("/");
            Parsed parsed = switch (elements.length) {
                case 3 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], "jar", null), elements[2]);
                case 4 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], elements[2], null), elements[3]);
                case 5 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], elements[2], elements[3]), elements[4]);
                default -> null;
            };
            if (parsed == null) {
                throw new IllegalArgumentException("Insufficient Maven coordinate: " + name);
            }
            deps.putIfAbsent(parsed.key(), new MavenDependencyValue(
                    parsed.version(),
                    MavenDependencyScope.COMPILE,
                    null,
                    null,
                    null));
        }
        String version = buildVersion != null && !buildVersion.isEmpty() ? buildVersion : self.version();
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(POM))) {
            emitter.emit(
                    self.key().groupId(),
                    self.key().artifactId(),
                    version,
                    "jar".equals(self.key().type()) ? null : self.key().type(),
                    deps,
                    metadata).accept(writer);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private record Parsed(MavenDependencyKey key, String version) {
    }
}
