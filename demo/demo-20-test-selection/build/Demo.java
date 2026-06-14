package build;

import module java.base;

public class Demo {

    static void main(String[] args) throws Exception {
        Path source = Path.of("sources", "calc", "Adder.java");
        Path ran = Path.of("target", "ran");
        String original = Files.readString(source);
        try {
            build();
            require(ran, "AdderTest", true);
            require(ran, "SubtractorTest", true);
            System.out.println("First build ran both tests.");

            delete(ran);
            Files.writeString(source, original.replace(
                    "return left + right;",
                    "int sum = left + right;\n        return sum;"));
            build();
            require(ran, "AdderTest", true);
            require(ran, "SubtractorTest", false);
            System.out.println("Editing Adder re-ran only AdderTest; SubtractorTest stayed cached.");
        } finally {
            Files.writeString(source, original);
        }
    }

    private static void build() throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseGet(() -> Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString());
        Process process = new ProcessBuilder(
                java,
                "-Djenesis.test.incremental",
                "build/jenesis/Project.java")
                .inheritIO()
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Build exited with a non-zero status");
        }
    }

    private static void require(Path directory, String marker, boolean expected) {
        if (Files.exists(directory.resolve(marker)) != expected) {
            throw new AssertionError(expected
                    ? marker + " was expected to run but did not"
                    : marker + " was expected to stay cached but ran");
        }
    }

    private static void delete(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }
}
