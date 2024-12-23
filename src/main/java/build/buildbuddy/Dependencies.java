package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
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

    public static final String FOLDER = "libs/";

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
        Path dependencies = Files.createDirectory(context.next().resolve(FOLDER));
        for (BuildStepArgument result : arguments.values()) {
            for (Path path : result.files().keySet()) {
                if (path.toString().endsWith(".dependencies")) {
                    Properties properties = new Properties();
                    try (InputStream inputStream = Files.newInputStream(result.folder().resolve(path))) {
                        properties.load(inputStream);
                    }
                    for (String dependency : properties.stringPropertyNames()) {
                        String[] segments = dependency.split(":", 2);
                        Repository repository = requireNonNull(
                                repositories.get(segments.length == 1 ? "" : segments[0]),
                                "Could not resolve dependency: " + dependency);
                        String[] expectation = properties.getProperty(dependency).split(":", 2);
                        MessageDigest digest;
                        try {
                            digest = MessageDigest.getInstance(expectation.length == 1 ? "SHA256" : expectation[0]);
                        } catch (NoSuchAlgorithmException e) {
                            throw new IllegalStateException(e);
                        }
                        if (context.previous() != null && Files.exists(context.previous().resolve(FOLDER + dependency))) {
                            Path file = context.previous().resolve(FOLDER + dependency);
                            if (validateAndLinkFile(digest, file, expectation[expectation.length == 1 ? 0 : 1])) {
                                Files.createLink(dependencies.resolve(dependency), file);
                                continue;
                            } else {
                                digest.reset();
                            }
                        }
                        CompletableFuture<?> future = new CompletableFuture<>();
                        executor.execute(() -> {
                            try {
                                Repository.InputStreamSource source = repository.fetch(segments[segments.length == 1 ? 0 : 1]);
                                Path file = source.getPath().orElse(null);
                                if (file == null) {
                                    try (
                                            DigestInputStream inputStream = new DigestInputStream(source.toInputStream(), digest);
                                            OutputStream outputStream = Files.newOutputStream(dependencies.resolve(dependency))
                                    ) {
                                        inputStream.transferTo(outputStream);
                                        if (!Arrays.equals(
                                                inputStream.getMessageDigest().digest(),
                                                Base64.getDecoder().decode(expectation[expectation.length == 1 ? 0 : 1]))) {
                                            throw new IllegalStateException("Mismatched digest for " + dependency);
                                        }
                                    }
                                } else {
                                    if (validateAndLinkFile(digest, file, expectation[expectation.length == 1 ? 0 : 1])) {
                                        Files.createLink(context.next().resolve(FOLDER + dependency), file);
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

    private boolean validateAndLinkFile(MessageDigest digest, Path file, String expected) throws IOException{
        try (FileChannel channel = FileChannel.open(file)) {
            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
        return Arrays.equals(digest.digest(), Base64.getDecoder().decode(expected));
    }
}
