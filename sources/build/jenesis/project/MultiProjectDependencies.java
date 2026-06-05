package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;

public class MultiProjectDependencies implements BuildStep {

    private final Predicate<String> isModule;
    private final String filterLabel;
    private final HashDigestFunction digest;

    public <P extends Predicate<String> & Serializable> MultiProjectDependencies(P isModule,
                                                                                 String filterLabel,
                                                                                 HashDigestFunction digest) {
        this.isModule = isModule;
        this.filterLabel = filterLabel;
        this.digest = digest;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(SCOPES),
                Path.of(REQUIRES),
                Path.of(VERSIONS),
                Path.of(EXCLUSIONS),
                Path.of(IDENTITY),
                Path.of(ARTIFACTS)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, String> coordinates = new LinkedHashMap<>(),
                dependencies = new LinkedHashMap<>(),
                versions = new LinkedHashMap<>(),
                exclusions = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            if (isModule.test(entry.getKey())) {
                Path scopesFile = entry.getValue().folder().resolve(SCOPES);
                boolean scoped = Files.exists(scopesFile);
                Set<String> filtered = new LinkedHashSet<>();
                if (scoped) {
                    SequencedProperties scopesProperties = SequencedProperties.ofFiles(scopesFile);
                    for (String property : scopesProperties.stringPropertyNames()) {
                        if (List.of(scopesProperties.getProperty(property).split(",")).contains(filterLabel)) {
                            filtered.add(property);
                        }
                    }
                }
                Path requiresPath = entry.getValue().folder().resolve(REQUIRES);
                if (Files.exists(requiresPath)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(requiresPath);
                    properties.stringPropertyNames().forEach(property -> {
                        if (!scoped || filtered.contains(property)) {
                            dependencies.put(property, properties.getProperty(property));
                        }
                    });
                }
                Path versionsPath = entry.getValue().folder().resolve(VERSIONS);
                if (Files.exists(versionsPath)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(versionsPath);
                    properties.stringPropertyNames().forEach(property -> versions.putIfAbsent(
                            property,
                            properties.getProperty(property)));
                }
                Path exclusionsPath = entry.getValue().folder().resolve(EXCLUSIONS);
                if (Files.exists(exclusionsPath)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(exclusionsPath);
                    properties.stringPropertyNames().forEach(property -> {
                        if (!scoped || filtered.contains(property)) {
                            exclusions.putIfAbsent(property, properties.getProperty(property));
                        }
                    });
                }
            } else {
                Path file = entry.getValue().folder().resolve(IDENTITY);
                if (Files.exists(file)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(file);
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
        SequencedProperties properties = new SequencedProperties();
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            String candidate = coordinates.get(entry.getKey());
            properties.setProperty(entry.getKey(),
                    candidate != null && !candidate.isEmpty()
                            ? digest.encodedHash(Path.of(candidate))
                            : entry.getValue());
        }
        properties.store(context.next().resolve(REQUIRES));
        if (!versions.isEmpty()) {
            SequencedProperties versionProperties = new SequencedProperties();
            versions.forEach(versionProperties::setProperty);
            versionProperties.store(context.next().resolve(VERSIONS));
        }
        if (!exclusions.isEmpty()) {
            SequencedProperties exclusionsProperties = new SequencedProperties();
            exclusions.forEach(exclusionsProperties::setProperty);
            exclusionsProperties.store(context.next().resolve(EXCLUSIONS));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
