package build.jenesis.project;

import module java.base;

public final class ProjectWatch {

    private final Path root;
    private final Set<Path> excluded;
    private final long debounceMillis;

    public ProjectWatch(Path root, Set<Path> excluded, long debounceMillis) {
        this.root = root;
        this.excluded = excluded;
        this.debounceMillis = debounceMillis;
    }

    public void watch(Runnable build) throws IOException {
        try (WatchService service = root.getFileSystem().newWatchService()) {
            register(service, root);
            build.run();
            System.out.println("Watching " + root + " for changes (press Ctrl+C to stop).");
            while (!Thread.interrupted()) {
                WatchKey key = service.take();
                boolean rebuild = false;
                while (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            rebuild = true;
                            continue;
                        }
                        Path changed = ((Path) key.watchable()).resolve((Path) event.context());
                        if (isExcluded(changed)) {
                            continue;
                        }
                        rebuild = true;
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                            register(service, changed);
                        }
                    }
                    key.reset();
                    key = service.poll(debounceMillis, TimeUnit.MILLISECONDS);
                }
                if (rebuild) {
                    System.out.println("Change detected, rebuilding.");
                    build.run();
                }
            }
        } catch (InterruptedException _) {
        }
    }

    private void register(WatchService service, Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (isExcluded(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                directory.register(service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcluded(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path exclude : excluded) {
            if (absolute.startsWith(exclude)) {
                return true;
            }
        }
        if (absolute.startsWith(root) && !absolute.equals(root)) {
            for (Path element : root.relativize(absolute)) {
                if (element.toString().startsWith(".")) {
                    return true;
                }
            }
        }
        return false;
    }
}
