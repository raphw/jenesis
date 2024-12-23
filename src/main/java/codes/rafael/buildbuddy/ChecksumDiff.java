package codes.rafael.buildbuddy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public class ChecksumDiff implements BiFunction<Path, Path, Map<Path, ChecksumDiff.State>> {

    @Override
    public Map<Path, State> apply(Path target, Path file) {
        Map<Path, State> states = new LinkedHashMap<>();
        if (Files.exists(file)) {
            Map<Path, byte[]> checksums = new LinkedHashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(target)) {
                checksums.put(Paths.get(reader.readLine()), reader.readLine().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            traverse(file, (path, bytes) -> {
                byte[] previous = checksums.remove(path);
                if (previous == null) {
                    states.put(path, new State(Status.ADDED, bytes, null));
                } else if (Arrays.equals(previous, bytes)) {
                    states.put(path, new State(Status.UNCHANGED, bytes, bytes));
                } else {
                    states.put(path, new State(Status.CHANGED, bytes, previous));
                }
            });
            checksums.forEach((path, bytes) -> states.put(path, new State(Status.REMOVED, null, bytes)));
        } else {
            traverse(file, (path, bytes) -> states.put(path, new State(Status.REMOVED, bytes, null)));
        }
        return states;
    }

    private static void traverse(Path root, BiConsumer<Path, byte[]> callback) {
        Queue<Path> queue = new ArrayDeque<>(List.of(root));
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            do {
                Path current = queue.remove();
                if (Files.isDirectory(current)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                        stream.forEach(queue::add);
                    }
                } else {digest.reset();
                    try (FileChannel channel = FileChannel.open(current)) {
                        digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    callback.accept(root.relativize(current), digest.digest());
                }
            } while (!queue.isEmpty());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record State(Status status, byte[] checksum, byte[] previous) { }

    public enum Status { ADDED, REMOVED, CHANGED, UNCHANGED }
}
