package build.buildbuddy.project;

import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.SequencedProperties;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class MultiProjectDependencies implements BuildStep {

    private final String algorithm, module;

    public MultiProjectDependencies(String algorithm, String module) {
        this.algorithm = algorithm;
        this.module = module;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, String> coordinates = new LinkedHashMap<>(), dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            if (entry.getKey().startsWith(BuildExecutorModule.PREVIOUS + MultiProjectModule.MODULE + "/" + module)) {
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
            } else if (entry.getKey().startsWith(BuildExecutorModule.PREVIOUS + MultiProjectModule.MODULE + "/")) {
                Path file = entry.getValue().folder().resolve(COORDINATES);
                if (Files.exists(file)) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(file)) {
                        properties.load(reader);
                    }
                    properties.stringPropertyNames().forEach(property -> {
                        String value = properties.getProperty(property);
                        if (!value.isEmpty()) {
                            coordinates.put(property, value);
                        }
                    });
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
            if (candidate != null) {
                if (candidate.isEmpty()) {
                    try (FileChannel channel = FileChannel.open(Path.of(candidate))) {
                        digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                    }
                    value = algorithm + "/" + HexFormat.of().formatHex(digest.digest());
                    digest.reset();
                } else {
                    value = candidate;
                }
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
