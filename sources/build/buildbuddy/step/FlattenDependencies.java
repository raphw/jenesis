package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public class FlattenDependencies implements BuildStep {

    public static final String DEPENDENCIES = "dependencies/";

    private final Map<String, Resolver> resolvers;
    private final Map<String, Repository> repositories;
    private final String algorithm;

    public FlattenDependencies(Map<String, Resolver> resolvers, Map<String, Repository> repositories, String algorithm) {
        this.resolvers = resolvers;
        this.repositories = repositories;
        this.algorithm = algorithm;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        Path flattened = Files.createDirectory(context.next().resolve(DownloadDependencies.FLATTENED));
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            Path folder = entry.getValue().folder().resolve(DEPENDENCIES);
            if (!Files.exists(folder)) {
                continue;
            }
            Map<String, SequencedMap<String, String>> groups = new LinkedHashMap<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.properties")) {
                for (Path path : stream) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(path)) {
                        properties.load(reader);
                    }
                    for (String property : properties.stringPropertyNames()) {
                        int index = property.indexOf('/');
                        groups
                                .computeIfAbsent(property.substring(0, index), ignored -> new LinkedHashMap<>())
                                .put(property.substring(index + 1), properties.getProperty(property));
                    }
                }
            }
            Properties properties = new SequencedProperties();
            for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
                for (String dependency : requireNonNull(
                        resolvers.get(group.getKey()),
                        "Unknown resolver: " + group.getKey()).dependencies(executor, group.getValue().keySet())) {
                    String expectation = group.getValue().getOrDefault(dependency, "");
                    if (!expectation.isEmpty()) {
                        properties.setProperty(dependency, expectation);
                    } else if (algorithm != null) {
                        MessageDigest digest;
                        try {
                            digest = MessageDigest.getInstance(algorithm);
                        } catch (NoSuchAlgorithmException e) {
                            throw new RuntimeException(e);
                        }
                        RepositoryItem item = requireNonNull(
                                repositories.get(group.getKey()),
                                "Unknown repository: " + group.getKey())
                                .fetch(executor, dependency)
                                .orElseThrow(() -> new IllegalStateException("Cannot resolve " + dependency));
                        Path file = item.getFile().orElse(null);
                        if (file == null) {
                            try (InputStream inputStream = item.toInputStream()) {
                                byte[] buffer = new byte[1024 * 8];
                                int length;
                                while ((length = inputStream.read(buffer, 0, buffer.length)) != -1) {
                                    digest.update(buffer, 0, length);
                                }
                            }
                        } else {
                            try (FileChannel channel = FileChannel.open(file)) {
                                digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                            }
                        }
                        properties.setProperty(
                                group.getKey() + "/" + dependency,
                                algorithm + "/" + Base64.getEncoder().encodeToString(digest.digest()));

                    } else {
                        properties.setProperty(dependency, "");
                    }
                }
            }
            try (Writer writer = Files.newBufferedWriter(flattened.resolve(entry.getKey() + ".properties"))) {
                properties.store(writer, null);
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
