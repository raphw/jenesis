package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;

public class MultiProjectDependencies implements BuildStep {

    private final String algorithm;
    private final Predicate<String> isModule;
    private final String scope;

    public <P extends Predicate<String> & Serializable> MultiProjectDependencies(String algorithm,
                                                                                 P isModule,
                                                                                 String scope) {
        this.algorithm = algorithm;
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
        Map<String, BuildStepArgument> coordinateOrigin = new HashMap<>();
        Map<String, Path> coordinateRelative = new HashMap<>();
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
                        if (List.of(scopesProperties.getProperty(property).split(",")).contains(scope)) {
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
                            coordinateOrigin.put(property, entry.getValue());
                            if (resolved.startsWith(folder)) {
                                coordinateRelative.put(property, folder.relativize(resolved));
                            }
                        }
                    }
                }
            }
        }
        Properties prior = new SequencedProperties();
        if (context.previous() != null) {
            Path priorFile = context.previous().resolve(REQUIRES);
            if (Files.exists(priorFile)) {
                try (Reader reader = Files.newBufferedReader(priorFile)) {
                    prior.load(reader);
                }
            }
        }
        String reusePrefix = algorithm + "/";
        Properties properties = new SequencedProperties();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            String candidate = coordinates.get(entry.getKey());
            String value;
            if (candidate != null && !candidate.isEmpty()) {
                BuildStepArgument origin = coordinateOrigin.get(entry.getKey());
                Path relative = coordinateRelative.get(entry.getKey());
                String reused = prior.getProperty(entry.getKey());
                boolean canReuse = origin != null
                        && relative != null
                        && origin.files().get(Path.of(IDENTITY)) == ChecksumStatus.RETAINED
                        && origin.files().get(relative) == ChecksumStatus.RETAINED
                        && reused != null
                        && reused.startsWith(reusePrefix);
                if (canReuse) {
                    value = reused;
                } else {
                    try (FileChannel channel = FileChannel.open(Path.of(candidate))) {
                        digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                    }
                    value = algorithm + "/" + HexFormat.of().formatHex(digest.digest());
                    digest.reset();
                }
            } else {
                value = entry.getValue();
            }
            properties.setProperty(entry.getKey(), value);
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
