package build.buildbuddy.steps;

import build.buildbuddy.*;
import build.buildbuddy.maven.*;

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

    private final MavenPomResolver resolver;
    private final MavenRepository repository;
    private final String algorithm;

    public PropertyDependencies(MavenPomResolver resolver, MavenRepository repository, String algorithm) {
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
            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = new LinkedHashMap<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.properties")) {
                for (Path path : stream) {
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(folder.resolve(path))) {
                        properties.load(reader);
                    }
                    for (String dependency : properties.stringPropertyNames()) {
                        String[] elements = dependency.split("\\|");
                        dependencies.put(switch (elements.length) {
                            case 2 -> new MavenDependencyKey(elements[0], elements[1], "jar", null);
                            case 3 -> new MavenDependencyKey(elements[0], elements[1], elements[2], null);
                            case 4 -> new MavenDependencyKey(elements[0], elements[1], elements[3], elements[2]);
                            default -> throw new IllegalStateException("Invalid coordinate: " + dependency);
                        }, new MavenDependencyValue(properties.getProperty(dependency), MavenDependencyScope.COMPILE, null, null, null));
                    }
                }
            }
            Properties properties = new Properties();
            for (MavenDependency dependency : resolver.dependencies(Map.of(), dependencies)) {
                String coordinate = "maven"
                        + "|" + dependency.groupId()
                        + "|" + dependency.artifactId()
                        + "|" + dependency.artifactId()
                        + "|" + dependency.version()
                        + "|" + dependency.type()
                        + (dependency.classifier() == null ? "" : "|" + dependency.classifier());
                if (algorithm != null) {
                    MessageDigest digest;
                    try {
                        digest = MessageDigest.getInstance(algorithm);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    RepositoryItem item = repository.fetch(dependency.groupId(),
                            dependency.artifactId(),
                            dependency.version(),
                            dependency.type(),
                            dependency.classifier(),
                            null).orElseThrow(() -> new IllegalStateException("Cannot resolve " + dependency));
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
