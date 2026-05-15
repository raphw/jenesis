package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class MultiProjectDependencies implements BuildStep {

    private final Predicate<String> isModule;
    private final Scope scope;

    public <P extends Predicate<String> & Serializable> MultiProjectDependencies(P isModule, Scope scope) {
        this.isModule = isModule;
        this.scope = scope;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, String> coordinates = new LinkedHashMap<>(),
                dependencies = new LinkedHashMap<>(),
                versions = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            if (isModule.test(entry.getKey())) {
                Path scopesFile = entry.getValue().folder().resolve(SCOPES);
                Set<String> filtered = new LinkedHashSet<>();
                if (Files.exists(scopesFile)) {
                    Properties scopesProperties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(scopesFile)) {
                        scopesProperties.load(reader);
                    }
                    for (String property : scopesProperties.stringPropertyNames()) {
                        if (List.of(scopesProperties.getProperty(property).split(",")).contains(scope.name())) {
                            filtered.add(property);
                        }
                    }
                }
                Path requiresPath = entry.getValue().folder().resolve(REQUIRES);
                if (Files.exists(requiresPath)) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(requiresPath)) {
                        properties.load(reader);
                    }
                    properties.stringPropertyNames().forEach(property -> {
                        if (filtered.isEmpty() || filtered.contains(property)) {
                            dependencies.put(property, properties.getProperty(property));
                        }
                    });
                }
                Path versionsPath = entry.getValue().folder().resolve(VERSIONS);
                if (Files.exists(versionsPath)) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(versionsPath)) {
                        properties.load(reader);
                    }
                    properties.stringPropertyNames().forEach(property -> versions.putIfAbsent(
                            property,
                            properties.getProperty(property)));
                }
            } else {
                Path file = entry.getValue().folder().resolve(IDENTITY);
                if (Files.exists(file)) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(file)) {
                        properties.load(reader);
                    }
                    Path folder = entry.getValue().folder();
                    for (String property : properties.stringPropertyNames()) {
                        String value = properties.getProperty(property);
                        if (!value.isEmpty()) {
                            Path resolved = folder.resolve(value).normalize();
                            coordinates.put(property, resolved.toString());
                        }
                    }
                }
            }
        }
        Properties properties = new SequencedProperties();
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            String candidate = coordinates.get(entry.getKey());
            properties.setProperty(entry.getKey(),
                    candidate != null && !candidate.isEmpty() ? "" : entry.getValue());
        }
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(REQUIRES))) {
            properties.store(writer, null);
        }
        if (!versions.isEmpty()) {
            Properties versionProperties = new SequencedProperties();
            versions.forEach(versionProperties::setProperty);
            try (Writer writer = Files.newBufferedWriter(context.next().resolve(VERSIONS))) {
                versionProperties.store(writer, null);
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
