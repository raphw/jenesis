package codes.rafael.buildbuddy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ChecksumMd5Diff implements ChecksumDiff {

    @Override
    public Map<Path, ChecksumStatus> update(Path checksum, Path root) throws IOException {
        Map<Path, ChecksumStatus> status = new LinkedHashMap<>();
        Path updated = Files.createTempFile("checksum", ".diff");
        try (BufferedWriter writer = Files.newBufferedWriter(updated)) {
            diff(checksum, root, (path, state) -> {
                writer.append(path.toString());
                writer.newLine();
                writer.append(Base64.getEncoder().encodeToString(state.checksum()));
                writer.newLine();
                status.put(path, state.status());
            });
        }
        Files.move(updated, checksum, StandardCopyOption.REPLACE_EXISTING);
        return status;
    }

    @Override
    public Map<Path, ChecksumStatus> read(Path checksum, Path root) throws IOException {
        Map<Path, ChecksumStatus> status = new LinkedHashMap<>();
        diff(checksum, root, (path, state) -> status.put(path, state.status()));
        return status;
    }

    void diff(Path checksum, Path root, IOConsumer<State> consumer) throws IOException {
        if (Files.exists(checksum)) {
            Map<Path, byte[]> checksums = new LinkedHashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(checksum)) {
                Iterator<String> it = reader.lines().iterator();
                while (it.hasNext()) {
                    checksums.put(Paths.get(it.next()), Base64.getDecoder().decode(it.next()));
                }
            }
            traverse(root, (path, bytes) -> {
                byte[] previous = checksums.remove(path);
                if (previous == null) {
                    consumer.accept(path, new State(ChecksumStatus.ADDED, bytes, null));
                } else if (Arrays.equals(previous, bytes)) {
                    consumer.accept(path, new State(ChecksumStatus.RETAINED, bytes, bytes));
                } else {
                    consumer.accept(path, new State(ChecksumStatus.ALTERED, bytes, previous));
                }
            });
            for (Map.Entry<Path, byte[]> entry : checksums.entrySet()) {
                consumer.accept(entry.getKey(), new State(ChecksumStatus.REMOVED, null, entry.getValue()));
            }
        } else {
            traverse(root, (path, bytes) -> consumer.accept(path, new State(ChecksumStatus.ADDED, bytes, null)));
        }
    }

    private static void traverse(Path root, IOConsumer<byte[]> consumer) throws IOException {
        Queue<Path> queue = new ArrayDeque<>(List.of(root));
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            do {
                Path current = queue.remove();
                if (Files.isDirectory(current)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                        stream.forEach(queue::add);
                    }
                } else {
                    digest.reset();
                    try (FileChannel channel = FileChannel.open(current)) {
                        digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                    }
                    consumer.accept(root.relativize(current), digest.digest());
                }
            } while (!queue.isEmpty());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface IOConsumer<VALUE> {

        void accept(Path path, VALUE value) throws IOException;
    }

    record State(ChecksumStatus status, byte[] checksum, byte[] previous) { }
}
