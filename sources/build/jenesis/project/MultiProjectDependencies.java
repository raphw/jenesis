package build.jenesis.project;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

import module java.base;

public class MultiProjectDependencies implements BuildStep {

    private final String algorithm;
    private final Predicate<String> isModule;

    public MultiProjectDependencies(String algorithm, Predicate<String> isModule) {
        this.algorithm = algorithm;
        this.isModule = isModule;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, String> coordinates = new LinkedHashMap<>(), dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            if (isModule.test(entry.getKey())) {
                Path file = entry.getValue().folder().resolve(DEPENDENCIES);
                if (Files.exists(file)) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(file)) {
                        properties.load(reader);
                    }
                    properties.stringPropertyNames().forEach(property -> {
                        String value = properties.getProperty(property);
                        dependencies.put(property, value);
                    });
                }
            } else {
                Path file = entry.getValue().folder().resolve(COORDINATES);
                if (Files.exists(file)) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(file)) {
                        properties.load(reader);
                    }
                    for (String property : properties.stringPropertyNames()) {
                        String value = properties.getProperty(property);
                        if (!value.isEmpty()) {
                            coordinates.put(property, value);
                        }
                    }
                }
            }
        }
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
                try (FileChannel channel = FileChannel.open(Path.of(candidate))) {
                    digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                }
                value = algorithm + "/" + HexFormat.of().formatHex(digest.digest());
                digest.reset();
            } else {
                value = entry.getValue();
            }
            properties.setProperty(entry.getKey(), value);
        }
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(DEPENDENCIES))) {
            properties.store(writer, null);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
