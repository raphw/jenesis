package codes.rafael.buildbuddy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ChecksumDigestDiff implements ChecksumDiff {

    private final String algorithm;

    public ChecksumDigestDiff(String algorithm) {
        this.algorithm = algorithm;
    }

    @Override
    public Map<Path, ChecksumStatus> read(Path checksums, Path root) throws IOException {
        Map<Path, ChecksumStatus> status = new LinkedHashMap<>();
        diff(checksums, root, (path, state) -> status.put(path, state.status()));
        return status;
    }

    @Override
    public Map<Path, ChecksumStatus> update(Path checksums, Path root) throws IOException {
        Map<Path, ChecksumStatus> status = new LinkedHashMap<>();
        Path updated = Files.createTempFile("checksums", ".temp");
        try (BufferedWriter writer = Files.newBufferedWriter(updated)) {
            diff(checksums, root, (path, state) -> {
                writer.append(path.toString());
                writer.newLine();
                writer.append(Base64.getEncoder().encodeToString(state.checksum()));
                writer.newLine();
                status.put(path, state.status());
            });
        }
        Files.move(updated, checksums, StandardCopyOption.REPLACE_EXISTING);
        return status;
    }

    void diff(Path checksum, Path root, IOConsumer<State> consumer) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (Files.exists(checksum)) {
            Map<Path, byte[]> checksums = new LinkedHashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(checksum)) {
                Iterator<String> it = reader.lines().iterator();
                while (it.hasNext()) {
                    checksums.put(Paths.get(it.next()), Base64.getDecoder().decode(it.next()));
                }
            }
            traverse(root, digest, (path, bytes) -> {
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
            traverse(root, digest, (path, bytes) -> consumer.accept(path, new State(ChecksumStatus.ADDED, bytes,null)));
        }
    }

    private static void traverse(Path root, MessageDigest digest, IOConsumer<byte[]> consumer) throws IOException {
        Queue<Path> queue = new ArrayDeque<>(List.of(root));
        do {
            Path current = queue.remove();
            if (Files.isDirectory(current)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                    stream.forEach(queue::add);
                }
            } else {
                try (FileChannel channel = FileChannel.open(current)) {
                    digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                }
                consumer.accept(root.relativize(current), digest.digest());
                digest.reset();
            }
        } while (!queue.isEmpty());
    }

    @FunctionalInterface
    interface IOConsumer<VALUE> {

        void accept(Path path, VALUE value) throws IOException;
    }

    record State(ChecksumStatus status, byte[] checksum, byte[] previous) { }
}
