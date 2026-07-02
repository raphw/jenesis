package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class MultiProjectDependencies implements BuildStep {

    private final Predicate<String> isModule;

    public <P extends Predicate<String> & Serializable> MultiProjectDependencies(P isModule) {
        this.isModule = isModule;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(REQUIRES),
                Path.of(VERSIONS),
                Path.of(BOMS),
                Path.of(EXCLUSIONS),
                Path.of(IDENTITY),
                Path.of(ARTIFACTS)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, Path> coordinates = new LinkedHashMap<>();
        SequencedMap<String, String> dependencies = new LinkedHashMap<>(),
                versions = new LinkedHashMap<>(),
                boms = new LinkedHashMap<>(),
                exclusions = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            if (isModule.test(entry.getKey())) {
                Path requiresPath = entry.getValue().folder().resolve(REQUIRES);
                if (Files.exists(requiresPath)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(requiresPath);
                    properties.stringPropertyNames().forEach(property ->
                            dependencies.put(property, properties.getProperty(property)));
                }
                Path versionsPath = entry.getValue().folder().resolve(VERSIONS);
                if (Files.exists(versionsPath)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(versionsPath);
                    properties.stringPropertyNames().forEach(property -> versions.putIfAbsent(
                            property,
                            properties.getProperty(property)));
                }
                Path bomsPath = entry.getValue().folder().resolve(BOMS);
                if (Files.exists(bomsPath)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(bomsPath);
                    properties.stringPropertyNames().forEach(property -> boms.putIfAbsent(
                            property,
                            properties.getProperty(property)));
                }
                Path exclusionsPath = entry.getValue().folder().resolve(EXCLUSIONS);
                if (Files.exists(exclusionsPath)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(exclusionsPath);
                    properties.stringPropertyNames().forEach(property ->
                            exclusions.putIfAbsent(property, properties.getProperty(property)));
                }
            } else {
                Path file = entry.getValue().folder().resolve(IDENTITY);
                if (Files.exists(file)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(file);
                    Path folder = entry.getValue().folder();
                    for (String property : properties.stringPropertyNames()) {
                        String value = properties.getProperty(property);
                        if (!value.isEmpty()) {
                            coordinates.put(property, folder.resolve(value).normalize());
                        }
                    }
                }
            }
        }
        SequencedProperties properties = new SequencedProperties();
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            String key = entry.getKey();
            int second = key.indexOf('/', key.indexOf('/') + 1);
            Path candidate = coordinates.get(key.substring(second + 1));
            properties.setProperty(entry.getKey(),
                    candidate != null
                            ? fingerprint(arguments.values(), candidate)
                            : entry.getValue());
        }
        properties.store(context.next().resolve(REQUIRES));
        if (!versions.isEmpty()) {
            SequencedProperties versionProperties = new SequencedProperties();
            versions.forEach(versionProperties::setProperty);
            versionProperties.store(context.next().resolve(VERSIONS));
        }
        if (!boms.isEmpty()) {
            SequencedProperties bomProperties = new SequencedProperties();
            boms.forEach(bomProperties::setProperty);
            bomProperties.store(context.next().resolve(BOMS));
        }
        if (!exclusions.isEmpty()) {
            SequencedProperties exclusionsProperties = new SequencedProperties();
            exclusions.forEach(exclusionsProperties::setProperty);
            exclusionsProperties.store(context.next().resolve(EXCLUSIONS));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String fingerprint(Collection<BuildStepArgument> arguments, Path artifact) {
        for (BuildStepArgument argument : arguments) {
            if (artifact.startsWith(argument.folder())) {
                String checksum = argument.checksum(argument.folder().relativize(artifact));
                if (checksum != null) {
                    return checksum;
                }
            }
        }
        throw new IllegalStateException("No tracked checksum for sibling artifact: " + artifact);
    }
}
