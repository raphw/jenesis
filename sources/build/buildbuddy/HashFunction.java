package build.buildbuddy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@FunctionalInterface
public interface HashFunction {

    byte[] hash(Path file) throws IOException;

    static Map<Path, byte[]> read(Path file) throws IOException {
        Map<Path, byte[]> checksums = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            Iterator<String> it = reader.lines().iterator();
            while (it.hasNext()) {
                checksums.put(Paths.get(it.next()), Base64.getDecoder().decode(it.next()));
            }
        }
        return checksums;
    }

    static Map<Path, byte[]> read(Path folder, HashFunction hash) throws IOException {
        Map<Path, byte[]> checksums = new LinkedHashMap<>();
        Queue<Path> queue = new ArrayDeque<>(List.of(folder));
        do {
            Path current = queue.remove();
            if (Files.isDirectory(current)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                    stream.forEach(queue::add);
                }
            } else {
                checksums.put(folder.relativize(current), hash.hash(current));
            }
        } while (!queue.isEmpty());
        return checksums;
    }

    static void write(Path file, Map<Path, byte[]> checksums) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (Map.Entry<Path, byte[]> entry : checksums.entrySet()) {
                writer.append(entry.getKey().toString());
                writer.newLine();
                writer.append(Base64.getEncoder().encodeToString(entry.getValue()));
                writer.newLine();
            }
        }
    }

    static boolean areConsistent(Path folder, Map<Path, byte[]> checksums, HashFunction hash) throws IOException {
        Queue<Path> queue = new ArrayDeque<>(List.of(folder));
        do {
            Path current = queue.remove();
            if (Files.isDirectory(current)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                    stream.forEach(queue::add);
                }
            } else {
                byte[] checksum = checksums.remove(folder.relativize(current));
                if (checksum == null || !Arrays.equals(checksum, hash.hash(current))) {
                    return false;
                }
            }
        } while (!queue.isEmpty());
        return checksums.isEmpty();
    }
}
