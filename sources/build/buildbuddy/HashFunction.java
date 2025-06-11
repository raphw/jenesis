package build.buildbuddy;

import module java.base;

@FunctionalInterface
public interface HashFunction {

    byte[] hash(Path file) throws IOException;

    static HashFunction ofSize() {
        return file -> {
            long size = Files.size(file);
            byte[] hash = new byte[Long.BYTES];
            for (int index = Long.BYTES - 1; index >= 0; index--) {
                hash[index] = (byte) (size & 0xFF);
                size >>= Byte.SIZE;
            }
            return hash;
        };
    }

    static HashFunction ofLastModified() {
        return file -> {
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            byte[] hash = new byte[Long.BYTES];
            for (int index = Long.BYTES - 1; index >= 0; index--) {
                hash[index] = (byte) (lastModified & 0xFF);
                lastModified >>= Byte.SIZE;
            }
            return hash;
        };
    }

    static Map<Path, byte[]> read(Path file) throws IOException {
        Map<Path, byte[]> checksums = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            Iterator<String> it = reader.lines().iterator();
            while (it.hasNext()) {
                checksums.put(Paths.get(it.next()), HexFormat.of().parseHex(it.next()));
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
                writer.append(HexFormat.of().formatHex(entry.getValue()));
                writer.newLine();
            }
        }
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
