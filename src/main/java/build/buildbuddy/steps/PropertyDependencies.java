package build.buildbuddy.steps;

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

public class PropertyDependencies implements BuildStep {

    public static final String DEPENDENCIES = "dependencies/";

    private final Resolver resolver;
    private final Repository repository;
    private final String algorithm;

    public PropertyDependencies(Resolver resolver, Repository repository, String algorithm) {
        this.resolver = resolver;
        this.repository = repository;
        this.algorithm = algorithm;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        Path flattened = Files.createDirectory(context.next().resolve(Dependencies.FLATTENED));
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            Path folder = entry.getValue().folder().resolve(DEPENDENCIES);
            if (!Files.exists(folder)) {
                continue;
            }
            List<String> dependencies = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.properties")) {
                for (Path path : stream) {
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(folder.resolve(path))) {
                        properties.load(reader);
                    }
                    dependencies.addAll(properties.stringPropertyNames());
                }
            }
            Properties properties = new Properties();
            for (String coordinate : resolver.dependencies(dependencies)) {
                if (algorithm != null) {
                    MessageDigest digest;
                    try {
                        digest = MessageDigest.getInstance(algorithm);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    RepositoryItem item = repository
                            .fetch(coordinate)
                            .orElseThrow(() -> new IllegalStateException("Cannot resolve " + coordinate));
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
                            coordinate,
                            algorithm + "|" + Base64.getEncoder().encodeToString(digest.digest()));

                } else {
                    properties.setProperty(coordinate, "");
                }
            }
            try (Writer writer = Files.newBufferedWriter(flattened.resolve(entry.getKey() + ".properties"))) {
                properties.store(new CommentSuppressingWriter(writer), null);
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
