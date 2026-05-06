package build.jenesis.maven;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

import module java.base;

public class Pom implements BuildStep {

    public static final String POM = "pom.xml";

    private final Function<String, String> resolver;
    private final MavenPomEmitter emitter = new MavenPomEmitter();

    public Pom() {
        this(coordinate -> {
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

    public Pom(Function<String, String> resolver) {
        this.resolver = resolver;
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
            Parsed parsed = parse(resolved.substring(separator + 1));
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
            Parsed parsed = parse(name.substring(separator + 1));
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
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(POM))) {
            emitter.emit(
                    self.key().groupId(),
                    self.key().artifactId(),
                    self.version(),
                    "jar".equals(self.key().type()) ? null : self.key().type(),
                    deps).accept(writer);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static Parsed parse(String coordinate) {
        String[] elements = coordinate.split("/");
        return switch (elements.length) {
            case 3 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], "jar", null), elements[2]);
            case 4 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], elements[2], null), elements[3]);
            case 5 -> new Parsed(new MavenDependencyKey(elements[0], elements[1], elements[2], elements[3]), elements[4]);
            default -> null;
        };
    }

    private record Parsed(MavenDependencyKey key, String version) {
    }
}
