package build.jenesis;

import module java.base;

public record BuildExecutorFileCache(Path root,
                                     String algorithm,
                                     boolean links,
                                     int steps,
                                     int versions,
                                     boolean touch,
                                     boolean lru,
                                     boolean compressed,
                                     boolean read,
                                     boolean write) implements BuildExecutorCache {

    public BuildExecutorFileCache(Path root) {
        Path file = root.resolve("cache.properties");
        SequencedProperties properties = new SequencedProperties();
        if (Files.isRegularFile(file)) {
            try {
                properties = SequencedProperties.ofFiles(file);
            } catch (IOException _) {
            }
        }
        String digest = properties.getProperty("digest"),
                steps = properties.getProperty("steps"),
                versions = properties.getProperty("versions"),
                touch = properties.getProperty("touch"),
                lru = properties.getProperty("lru"),
                compressed = properties.getProperty("compressed"),
                read = properties.getProperty("read"),
                write = properties.getProperty("write");
        this(root,
                digest == null ? "SHA-256" : digest,
                true,
                steps == null ? 250 : Integer.parseInt(steps.trim()),
                versions == null ? 10 : Integer.parseInt(versions.trim()),
                touch == null || Boolean.parseBoolean(touch.trim()),
                lru == null || Boolean.parseBoolean(lru.trim()),
                compressed != null && Boolean.parseBoolean(compressed.trim()),
                read == null || Boolean.parseBoolean(read.trim()),
                write == null || Boolean.parseBoolean(write.trim()));
    }

    @Override
    public Optional<BuildStepResult> fetch(Executor executor,
                                           String identity,
                                           byte[] step,
                                           SequencedMap<String, Map<Path, byte[]>> inputs,
                                           Path target) throws IOException {
        if (!read) {
            return Optional.empty();
        }
        Path folder = root.resolve(HexFormat.of().formatHex(step));
        Path entry = folder.resolve(HexFormat.of().formatHex(fold(inputs)));
        if (Files.isDirectory(entry)) {
            materialize(entry, target, links);
        } else if (Files.isRegularFile(entry)) {
            unzip(entry, target);
        } else {
            return Optional.empty();
        }
        if (touch && write) {
            touch(entry);
            touch(folder);
        }
        return Optional.of(new BuildStepResult(true));
    }

    @Override
    public void store(Executor executor,
                      String identity,
                      byte[] step,
                      SequencedMap<String, Map<Path, byte[]>> inputs,
                      Path output) throws IOException {
        if (!write) {
            return;
        }
        try {
            executor.execute(() -> persist(step, inputs, output));
        } catch (RejectedExecutionException _) {
        }
    }

    private void persist(byte[] step, SequencedMap<String, Map<Path, byte[]>> inputs, Path output) {
        Path folder = root.resolve(HexFormat.of().formatHex(step));
        Path entry = folder.resolve(HexFormat.of().formatHex(fold(inputs)));
        if (Files.exists(entry)) {
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(folder);
            temporary = compressed
                    ? Files.createTempFile(folder, "tmp", null)
                    : Files.createTempDirectory(folder, "tmp");
            if (compressed) {
                zip(output, temporary);
            } else {
                materialize(output, temporary, false);
            }
            try {
                Files.move(temporary, entry, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temporary, entry);
            }
            temporary = null;
            evict(folder, versions);
            evict(root, steps);
        } catch (IOException | RuntimeException _) {
        } finally {
            if (temporary != null) {
                try {
                    delete(temporary);
                } catch (IOException _) {
                }
            }
        }
    }

    private byte[] fold(SequencedMap<String, Map<Path, byte[]>> inputs) {
        MessageDigest message;
        try {
            message = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        inputs.forEach((argument, files) -> {
            message.update(argument.getBytes(StandardCharsets.UTF_8));
            message.update((byte) 0);
            files.forEach((path, hash) -> {
                message.update(path.toString().replace(File.separatorChar, '/').getBytes(StandardCharsets.UTF_8));
                message.update((byte) 0);
                message.update(hash);
            });
        });
        return message.digest();
    }

    private void evict(Path parent, int limit) throws IOException {
        if (limit <= 0) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path path : stream) {
                if (isHex(path.getFileName().toString())) {
                    entries.add(path);
                }
            }
        }
        int excess = entries.size() - limit;
        if (excess <= 0) {
            return;
        }
        entries.sort(Comparator.comparing(BuildExecutorFileCache::lastModified));
        for (int index = 0; index < excess; index++) {
            try {
                delete(lru ? entries.get(index) : entries.get(entries.size() - 1 - index));
            } catch (IOException _) {
            }
        }
    }

    private static void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
        } catch (IOException _) {
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException _) {
            return FileTime.fromMillis(0);
        }
    }

    private static boolean isHex(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.digit(name.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void materialize(Path source, Path target, boolean links) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path destination = target.resolve(source.relativize(file));
                if (links) {
                    try {
                        Files.createLink(destination, file);
                        return FileVisitResult.CONTINUE;
                    } catch (UnsupportedOperationException | FileSystemException _) {
                    }
                }
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void zip(Path source, Path target) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    zip.putNextEntry(new ZipEntry(source.relativize(file).toString().replace(File.separatorChar, '/')));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void unzip(Path source, Path target) throws IOException {
        Path base = target.normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path destination = base.resolve(entry.getName()).normalize();
                if (!destination.startsWith(base)) {
                    throw new IOException("Bad cache entry: " + entry.getName());
                }
                Files.createDirectories(destination.getParent());
                Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void delete(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
