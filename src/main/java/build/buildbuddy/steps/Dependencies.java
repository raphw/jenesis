package build.buildbuddy.steps;

import build.buildbuddy.*;
import build.buildbuddy.maven.MavenRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public class Dependencies implements BuildStep {

    public static final String FLATTENED = "flattened/", LIBS = "libs/";

    private final Map<String, Repository> repositories;

    public Dependencies() {
        repositories = Map.of("maven", new MavenRepository());
    }

    public Dependencies(Map<String, Repository> repositories) {
        this.repositories = repositories;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        Path libs = Files.createDirectory(context.next().resolve(LIBS));
        for (BuildStepArgument result : arguments.values()) {
            Path dependencies = result.folder().resolve(FLATTENED);
            if (!Files.exists(dependencies)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dependencies, "*.properties")) {
                for (Path path : stream) {
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(result.folder().resolve(path))) {
                        properties.load(reader);
                    }
                    for (String property : properties.stringPropertyNames()) {
                        int index = property.indexOf('/');
                        String expectation = properties.getProperty(property),
                                dependency = property.replace('/', ':'),
                                coordinate = dependency.substring(index + 1);
                        Repository repository = requireNonNull(
                                repositories.get(dependency.substring(0, index)),
                                "Could not resolve repository: " + dependency.substring(0, index));
                        // TODO: make digest optional.
                        int algorithm = expectation.indexOf('/');
                        MessageDigest digest;
                        try {
                            digest = MessageDigest.getInstance(algorithm == -1 ? "SHA256" : expectation.substring(0, algorithm));
                        } catch (NoSuchAlgorithmException e) {
                            throw new IllegalStateException(e);
                        }
                        String checksum = algorithm == -1 ? expectation : expectation.substring(algorithm + 1);
                        if (context.previous() != null && Files.exists(context.previous().resolve(LIBS + dependency))) {
                            Path file = context.previous().resolve(LIBS + dependency);
                            if (validateAndLinkFile(digest, file, checksum)) {
                                Files.createLink(libs.resolve(dependency), file);
                                continue;
                            } else {
                                digest.reset();
                            }
                        }
                        CompletableFuture<?> future = new CompletableFuture<>();
                        executor.execute(() -> {
                            try {
                                RepositoryItem source = repository
                                        .fetch(coordinate)
                                        .orElseThrow(() -> new IllegalStateException("Could not fetch " + coordinate));
                                Path file = source.getFile().orElse(null);
                                if (file == null) {
                                    try (DigestInputStream inputStream = new DigestInputStream(source.toInputStream(), digest)) {
                                        Files.copy(inputStream, libs.resolve(dependency));
                                        if (!Arrays.equals(
                                                inputStream.getMessageDigest().digest(),
                                                Base64.getDecoder().decode(checksum))) {
                                            throw new IllegalStateException("Mismatched digest for " + dependency);
                                        }
                                    }
                                } else {
                                    if (validateAndLinkFile(digest, file, checksum)) {
                                        Files.createLink(context.next().resolve(LIBS + dependency), file);
                                    } else {
                                        throw new IllegalStateException("Mismatched digest for " + dependency);
                                    }
                                }
                                future.complete(null);
                            } catch (Throwable t) {
                                future.completeExceptionally(t);
                            }
                        });
                        futures.add(future);
                    }
                }
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> new BuildStepResult(true));
    }

    private boolean validateAndLinkFile(MessageDigest digest, Path file, String expected) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
        return Arrays.equals(digest.digest(), Base64.getDecoder().decode(expected));
    }
}
