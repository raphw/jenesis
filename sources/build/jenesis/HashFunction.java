package build.jenesis;

import module java.base;

@FunctionalInterface
public interface HashFunction {

    byte[] hash(Path file) throws IOException;

    static Map<Path, byte[]> read(Path file) throws IOException {
        Map<Path, byte[]> checksums = new LinkedHashMap<>();
        SequencedProperties properties = SequencedProperties.ofFiles(file);
        for (String name : properties.stringPropertyNames()) {
            checksums.put(Path.of(name), HexFormat.of().parseHex(properties.getProperty(name)));
        }
        return checksums;
    }

    static Map<Path, byte[]> read(Path folder, HashFunction hash) throws IOException {
        Map<Path, byte[]> checksums = new LinkedHashMap<>();
        if (!Files.exists(folder)) {
            return checksums;
        }
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
        SequencedProperties properties = new SequencedProperties();
        checksums.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey().toString().replace(File.separatorChar, '/'),
                        HexFormat.of().formatHex(entry.getValue())))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> properties.setProperty(entry.getKey(), entry.getValue()));
        properties.store(file);
    }

    static boolean areConsistent(Path folder, Map<Path, byte[]> checksums, HashFunction hash) throws IOException {
        Map<Path, byte[]> remaining = new HashMap<>(checksums);
        Queue<Path> queue = new ArrayDeque<>(List.of(folder));
        do {
            Path current = queue.remove();
            if (Files.isDirectory(current)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                    stream.forEach(queue::add);
                }
            } else {
                byte[] checksum = remaining.remove(folder.relativize(current));
                if (checksum == null || !Arrays.equals(checksum, hash.hash(current))) {
                    return false;
                }
            }
        } while (!queue.isEmpty());
        return remaining.isEmpty();
    }
}
