package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Checksum implements DependencyTransformingBuildStep {

    private final String algorithm;
    private final Map<String, Repository> repositories;

    public Checksum(String algorithm, Map<String, Repository> repositories) {
        this.algorithm = algorithm;
        this.repositories = repositories;
    }

    @Override
    public CompletionStage<Properties> transform(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> groups)
            throws IOException {
        Properties properties = new SequencedProperties();
        for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
            Repository repository = repositories.getOrDefault(group.getKey(), Repository.empty());
            for (Map.Entry<String, String> entry : group.getValue().entrySet()) {
                String dependency = group.getKey() + "/" + entry.getKey();
                if (!entry.getValue().isEmpty()) {
                    properties.setProperty(dependency, entry.getValue());
                } else {
                    MessageDigest digest;
                    try {
                        digest = MessageDigest.getInstance(algorithm);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    RepositoryItem item = repository.fetch(executor, dependency).orElseThrow(
                            () -> new IllegalStateException("Cannot resolve " + dependency));
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
                            digest.update(channel.map(
                                    FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                        }
                    }
                    properties.setProperty(dependency, algorithm + "/" + HexFormat.of().formatHex(digest.digest()));

                }
            }
        }
        return CompletableFuture.completedStage(properties);
    }
}
