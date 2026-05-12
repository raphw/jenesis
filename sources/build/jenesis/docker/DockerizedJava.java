package build.jenesis.docker;

import module java.base;

public class DockerizedJava {

    public static final String JAVA_HOME_MOUNT = "/opt/java-home";

    public static final String IMPLICIT_DOCKERFILE = "FROM debian:stable-slim\n";

    private final String image;
    private final Path workingDirectory;
    private final Map<Path, String> mounts;

    public DockerizedJava(Path workingDirectory) throws IOException, InterruptedException {
        String image;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(IMPLICIT_DOCKERFILE.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            image = "jenesis-build:" + builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Process inspect = new ProcessBuilder("docker", "image", "inspect", image)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (inspect.waitFor() != 0) {
            Process build = new ProcessBuilder("docker", "build", "-q", "-t", image, "-")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            try (OutputStream out = build.getOutputStream()) {
                out.write(IMPLICIT_DOCKERFILE.getBytes(StandardCharsets.UTF_8));
            }
            int code = build.waitFor();
            if (code != 0) {
                throw new IOException("Failed to build implicit Docker image " + image + ": exit code " + code);
            }
        }
        this(workingDirectory, image);
    }

    public DockerizedJava(Path workingDirectory, String image) {
        this(workingDirectory, image, Map.of());
    }

    private DockerizedJava(Path workingDirectory, String image, Map<Path, String> mounts) {
        this.image = image;
        this.workingDirectory = workingDirectory;
        this.mounts = mounts;
    }

    public String image() {
        return image;
    }

    public DockerizedJava mount(Path host, String container, boolean readOnly) {
        SequencedMap<Path, String> copy = new LinkedHashMap<>(mounts);
        copy.put(host.toAbsolutePath(), container + (readOnly ? ":ro" : ""));
        return new DockerizedJava(workingDirectory, image, copy);
    }

    public int execute(String main, Map<String, String> properties, String... args) throws IOException, InterruptedException {
        String home = System.getProperty("java.home");
        if (home == null) {
            home = System.getenv("JAVA_HOME");
        }
        if (home == null) {
            throw new IllegalStateException("Neither JAVA_HOME environment or java.home property set");
        }
        Path javaHome = Path.of(home).toAbsolutePath();
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
            docker.add("-D" + property.getKey() + "=" + property.getValue());
        }
        docker.add(main);
        docker.addAll(Arrays.asList(args));
        return new ProcessBuilder(docker).inheritIO().start().waitFor();
    }
}
