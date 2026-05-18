package build.jenesis.docker;

import module java.base;

public class DockerizedJava {

    public static final String IMPLICIT_DOCKERFILE_LINUX = "FROM debian:stable-slim\n";

    public static final String IMPLICIT_DOCKERFILE_WINDOWS = "FROM mcr.microsoft.com/windows/nanoserver:ltsc2025\n";

    public static final String JAVA_HOME_MOUNT_LINUX = "/opt/java-home";

    public static final String JAVA_HOME_MOUNT_WINDOWS = "C:\\opt\\java-home";

    public static final String IMPLICIT_DOCKERFILE = IMPLICIT_DOCKERFILE_LINUX;

    public static final String JAVA_HOME_MOUNT = JAVA_HOME_MOUNT_LINUX;

    private final String image;
    private final Path workingDirectory;
    private final Map<Path, String> mounts;
    private final Map<String, String> environment;
    private final Boolean windowsDaemon;

    public DockerizedJava(Path workingDirectory) throws IOException, InterruptedException {
        boolean windows = isWindowsDaemon();
        String dockerfile = windows ? IMPLICIT_DOCKERFILE_WINDOWS : IMPLICIT_DOCKERFILE_LINUX;
        String image;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(dockerfile.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                builder.append(String.format("%02x", digest[index]));
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
                out.write(dockerfile.getBytes(StandardCharsets.UTF_8));
            }
            int code = build.waitFor();
            if (code != 0) {
                throw new IOException("Failed to build implicit Docker image " + image + ": exit code " + code);
            }
        }
        this.workingDirectory = workingDirectory;
        this.image = image;
        this.windowsDaemon = windows;
        this.mounts = Map.of();
        this.environment = Map.of();
    }

    public DockerizedJava(Path workingDirectory, String image) {
        this(workingDirectory, image, null, Map.of(), Map.of());
    }

    private DockerizedJava(Path workingDirectory,
                           String image,
                           Boolean windowsDaemon,
                           Map<Path, String> mounts,
                           Map<String, String> environment) {
        this.image = image;
        this.workingDirectory = workingDirectory;
        this.windowsDaemon = windowsDaemon;
        this.mounts = mounts;
        this.environment = environment;
    }

    public String image() {
        return image;
    }

    public DockerizedJava mount(Path host, String container, boolean readOnly) {
        SequencedMap<Path, String> copy = new LinkedHashMap<>(mounts);
        copy.put(host.toAbsolutePath(), container + (readOnly ? ":ro" : ""));
        return new DockerizedJava(workingDirectory, image, windowsDaemon, copy, environment);
    }

    public DockerizedJava env(String name, String value) {
        SequencedMap<String, String> copy = new LinkedHashMap<>(environment);
        copy.put(name, value);
        return new DockerizedJava(workingDirectory, image, windowsDaemon, mounts, copy);
    }

    public int execute(String main, Map<String, String> properties, String... args) throws IOException, InterruptedException {
        List<String> javaArgs = new ArrayList<>();
        for (Map.Entry<String, String> property : properties.entrySet()) {
            javaArgs.add("-D" + property.getKey() + "=" + property.getValue());
        }
        javaArgs.add(main);
        javaArgs.addAll(Arrays.asList(args));
        return execute(javaArgs);
    }

    public int execute(List<String> javaArgs) throws IOException, InterruptedException {
        String home = System.getProperty("java.home");
        if (home == null) {
            home = System.getenv("JAVA_HOME");
        }
        if (home == null) {
            throw new IllegalStateException("Neither JAVA_HOME environment or java.home property set");
        }
        Path javaHome = Path.of(home).toAbsolutePath();
        boolean windows = windowsDaemon != null ? windowsDaemon : isWindowsDaemon();
        String javaHomeMount = windows ? JAVA_HOME_MOUNT_WINDOWS : JAVA_HOME_MOUNT_LINUX;
        List<String> docker = new ArrayList<>();
        docker.add("docker");
        docker.add("run");
        docker.add("--rm");
        docker.add("-i");
        if (!windows) {
            try {
                Object uid = Files.getAttribute(workingDirectory, "unix:uid");
                Object gid = Files.getAttribute(workingDirectory, "unix:gid");
                docker.add("--user");
                docker.add(uid + ":" + gid);
            } catch (UnsupportedOperationException | IllegalArgumentException _) {
            }
        }
        docker.add("-w");
        docker.add(workingDirectory.toString());
        docker.add("-v");
        docker.add(workingDirectory + ":" + workingDirectory);
        docker.add("-v");
        docker.add(javaHome + ":" + javaHomeMount + ":ro");
        for (Map.Entry<Path, String> mount : mounts.entrySet()) {
            docker.add("-v");
            docker.add(mount.getKey() + ":" + mount.getValue());
        }
        for (Map.Entry<String, String> variable : environment.entrySet()) {
            docker.add("-e");
            docker.add(variable.getKey() + "=" + variable.getValue());
        }
        docker.add(image);
        docker.add(javaHomeMount + (windows ? "\\bin\\java.exe" : "/bin/java"));
        docker.addAll(javaArgs);
        return new ProcessBuilder(docker).inheritIO().start().waitFor();
    }

    private static boolean isWindowsDaemon() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Os}}")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IOException("Failed to query Docker server OS: " + output);
        }
        return "windows".equals(output);
    }
}
