package build.jenesis.docker;

import module java.base;

public class Docker {

    public static final String JAVA_HOME_MOUNT = "/opt/java-home";

    public static final String IMPLICIT_DOCKERFILE = "FROM debian:stable-slim\n";

    private static final String PROPERTY_PREFIX = "jenesis.";

    private final String image;
    private final Path workingDirectory;
    private final SequencedMap<Path, String> mounts;

    public Docker(String image) {
        this(image, Path.of("").toAbsolutePath(), new LinkedHashMap<>());
    }

    private Docker(String image, Path workingDirectory, SequencedMap<Path, String> mounts) {
        this.image = image;
        this.workingDirectory = workingDirectory;
        this.mounts = mounts;
    }

    public Docker workingDirectory(Path workingDirectory) {
        return new Docker(image, workingDirectory.toAbsolutePath(), mounts);
    }

    public Docker mount(Path host, String container, boolean readOnly) {
        SequencedMap<Path, String> copy = new LinkedHashMap<>(mounts);
        copy.put(host.toAbsolutePath(), container + (readOnly ? ":ro" : ""));
        return new Docker(image, workingDirectory, copy);
    }

    public static String implicitImage() throws IOException, InterruptedException {
        String tag = "jenesis-build:" + hash(IMPLICIT_DOCKERFILE);
        Process inspect = new ProcessBuilder("docker", "image", "inspect", tag)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (inspect.waitFor() == 0) {
            return tag;
        }
        Process build = new ProcessBuilder("docker", "build", "-q", "-t", tag, "-")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try (OutputStream out = build.getOutputStream()) {
            out.write(IMPLICIT_DOCKERFILE.getBytes(StandardCharsets.UTF_8));
        }
        int code = build.waitFor();
        if (code != 0) {
            throw new IOException("Failed to build implicit Docker image " + tag + ": exit code " + code);
        }
        return tag;
    }

    private static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute(Map<String, String> properties, String... args) throws IOException, InterruptedException {
        String home = System.getProperty("java.home");
        if (home == null) {
            home = System.getenv("JAVA_HOME");
        }
        if (home == null) {
            throw new IllegalStateException("Neither JAVA_HOME environment or java.home property set");
        }
        Path javaHome = Path.of(home).toAbsolutePath();
        String mainCommand = System.getProperty("sun.java.command");
        if (mainCommand == null || mainCommand.isEmpty()) {
            throw new IllegalStateException("Cannot resolve main entry (sun.java.command not set)");
        }
        String entry = mainCommand.split(" ", 2)[0];
        List<String> docker = new ArrayList<>();
        docker.add("docker");
        docker.add("run");
        docker.add("--rm");
        docker.add("-i");
        docker.add("-w");
        docker.add(workingDirectory.toString());
        docker.add("-v");
        docker.add(workingDirectory + ":" + workingDirectory);
        docker.add("-v");
        docker.add(javaHome + ":" + JAVA_HOME_MOUNT + ":ro");
        for (Map.Entry<Path, String> mount : mounts.entrySet()) {
            docker.add("-v");
            docker.add(mount.getKey() + ":" + mount.getValue());
        }
        docker.add(image);
        docker.add(JAVA_HOME_MOUNT + "/bin/java");
        for (Map.Entry<String, String> property : properties.entrySet()) {
            if (property.getKey().startsWith(PROPERTY_PREFIX)) {
                docker.add("-D" + property.getKey() + "=" + property.getValue());
            }
        }
        if (entry.endsWith(".jar")) {
            docker.add("-jar");
        } else if (entry.contains("/") && !entry.startsWith("/")) {
            docker.add("-m");
        }
        docker.add(entry);
        docker.addAll(Arrays.asList(args));
        System.exit(new ProcessBuilder(docker).inheritIO().start().waitFor());
    }
}
