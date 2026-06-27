package build;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.Project;

public class Demo {

    static void main(String[] args) throws IOException {
        // Baseline: a version-only dependency resolves and builds by default.
        wipe();
        new Project().build("+unpinned");
        System.out.println("[ok]      unpinned: a version-only dependency builds by default");

        // 1. ... but strict pinning rejects it - there is no checksum to verify.
        expectFailure("unpinned: a version-only dependency under strict pinning",
                () -> new Project().pinning(Pinning.STRICT).build("+unpinned"));

        // 2. A wrong checksum fails the build even without strict pinning: every
        // download is verified against its pin regardless.
        expectFailure("tampered: a dependency whose pinned checksum does not match",
                () -> new Project().build("+tampered"));

        System.out.println();
        System.out.println("Both supply-chain checks blocked the build, as expected.");
    }

    private static void expectFailure(String description, Build build) throws IOException {
        wipe();
        try {
            build.run();
        } catch (Throwable _) {
            System.out.println("[blocked] " + description);
            return;
        }
        throw new AssertionError("Build was expected to fail but succeeded: " + description);
    }

    private static void wipe() throws IOException {
        Path target = Path.of("target");
        if (!Files.isDirectory(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @FunctionalInterface
    private interface Build {
        void run() throws Exception;
    }
}
