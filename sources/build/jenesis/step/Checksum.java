package build.jenesis.step;

import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.SequencedProperties;

import module java.base;

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
                    RepositoryItem item = repository.fetch(executor, entry.getKey()).orElseThrow(
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
