package build.jenesis;

import module java.base;
import build.jenesis.docker.DockerizedJava;
import build.jenesis.project.DependenciesModule;
import build.jenesis.project.DependencyScope;
import build.jenesis.project.MultiProjectModule;

public record Execute(Project project, String mainClass, String module) {

    public Execute(Project project) {
        this(project, null, null);
    }

    public Execute mainClass(String mainClass) {
        return new Execute(project, mainClass, module);
    }

    public Execute module(String module) {
        return new Execute(project, mainClass, module);
    }

    public Execute resolveProperties() {
        String mainOverride = System.getProperty("jenesis.execute.mainClass");
        String moduleOverride = System.getProperty("jenesis.execute.module");
        return new Execute(project,
                mainOverride != null ? mainOverride : mainClass,
                moduleOverride != null ? moduleOverride : module);
    }

    public int execute(String... arguments) throws IOException, InterruptedException {
        return doExecute(false, arguments);
    }

    private int doExecute(boolean mainMethod, String... arguments) throws IOException, InterruptedException {
        String moduleSegmentPrefix = MultiProjectModule.MODULE + "-";
        String runtimeArtifactsSuffix = "/" + DependencyScope.RUNTIME.label()
                + "/" + MultiProjectModule.DEPENDENCIES
                + "/" + DependenciesModule.ARTIFACTS;
        String targetSegment = module != null ? moduleSegmentPrefix + BuildExecutorModule.encode(module) : null;
        String selector = module != null ? "+" + module : Project.BUILD;
        SequencedMap<String, Path> outputs = mainMethod ? project.doMain(selector) : project.build(selector);
        SequencedMap<String, SequencedMap<String, Path>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : outputs.entrySet()) {
            String key = entry.getKey();
            String segment = null;
            for (String part : key.split("/")) {
                if (part.startsWith(moduleSegmentPrefix)) {
                    segment = part;
                    break;
                }
            }
            if (segment == null) {
                continue;
            }
            if (targetSegment != null && !targetSegment.equals(segment)) {
                continue;
            }
            groups.computeIfAbsent(segment, _ -> new LinkedHashMap<>()).put(key, entry.getValue());
        }
        SequencedMap<String, Candidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, SequencedMap<String, Path>> group : groups.entrySet()) {
            SequencedProperties mergedModule = new SequencedProperties();
            SequencedProperties mergedIdentity = new SequencedProperties();
            for (Path folder : group.getValue().values()) {
                Path moduleFile = folder.resolve(BuildStep.MODULE);
                if (Files.isRegularFile(moduleFile)) {
                    Properties loaded = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(moduleFile)) {
                        loaded.load(reader);
                    }
                    mergedModule.putAll(loaded);
                }
                Path identityFile = folder.resolve(BuildStep.IDENTITY);
                if (Files.isRegularFile(identityFile)) {
                    Properties loaded = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(identityFile)) {
                        loaded.load(reader);
                    }
                    boolean complete = true;
                    for (String name : loaded.stringPropertyNames()) {
                        String value = loaded.getProperty(name);
                        if (value == null || value.isEmpty()) {
                            complete = false;
                            break;
                        }
                    }
                    if (complete) {
                        for (String name : loaded.stringPropertyNames()) {
                            mergedIdentity.setProperty(name, folder.resolve(loaded.getProperty(name)).normalize().toString());
                        }
                    }
                }
            }
            String main;
            if (mainClass != null) {
                main = mainClass;
            } else {
                main = mergedModule.getProperty("main");
                if (main == null || main.isEmpty()) {
                    continue;
                }
            }
            candidates.put(group.getKey(), new Candidate(
                    mergedModule.getProperty("path", ""),
                    main,
                    mergedModule.getProperty("module"),
                    mergedIdentity,
                    group.getValue()));
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(module != null
                    ? "No module at path: " + (module.isEmpty() ? "<root>" : module)
                    : "No module declares a main class");
        }
        if (candidates.size() > 1) {
            StringBuilder message = new StringBuilder("Multiple modules declare a main class, select one explicitly:");
            for (Candidate candidate : candidates.values()) {
                message.append(System.lineSeparator())
                        .append("  ")
                        .append(candidate.path.isEmpty() ? "<root>" : candidate.path)
                        .append(" -> ")
                        .append(candidate.mainClass);
            }
            throw new IllegalStateException(message.toString());
        }
        Candidate candidate = candidates.values().iterator().next();
        Path mainArtifact = null;
        for (String name : candidate.identity.stringPropertyNames()) {
            Path resolved = Path.of(candidate.identity.getProperty(name));
            if (Files.isRegularFile(resolved)) {
                mainArtifact = resolved;
                break;
            }
        }
        if (mainArtifact == null) {
            throw new IllegalStateException("Did not find a main artifact for module: "
                    + (candidate.path.isEmpty() ? "<root>" : candidate.path));
        }
        List<String> jars = new ArrayList<>();
        jars.add(mainArtifact.toString());
        for (Map.Entry<String, Path> entry : candidate.folders.entrySet()) {
            if (!entry.getKey().endsWith(runtimeArtifactsSuffix)) {
                continue;
            }
            Path libraries = entry.getValue().resolve(DependenciesModule.ARTIFACTS);
            if (Files.isDirectory(libraries)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(libraries)) {
                    for (Path file : stream) {
                        jars.add(file.toString());
                    }
                }
            }
        }
        List<String> javaArgs = new ArrayList<>();
        if (candidate.module != null) {
            javaArgs.add("--module-path");
            javaArgs.add(String.join(File.pathSeparator, jars));
            javaArgs.add("-m");
            javaArgs.add(candidate.module + "/" + candidate.mainClass);
        } else {
            javaArgs.add("-cp");
            javaArgs.add(String.join(File.pathSeparator, jars));
            javaArgs.add(candidate.mainClass);
        }
        javaArgs.addAll(List.of(arguments));
        if (mainMethod && Boolean.getBoolean("jenesis.execute.docker")) {
            String image = System.getProperty("jenesis.execute.docker.image");
            Path root = project.root().toAbsolutePath().normalize();
            DockerizedJava docker = image == null ? new DockerizedJava(root) : new DockerizedJava(root, image);
            for (Path path : List.of(project.target(), project.cache())) {
                Path absolute = (path.isAbsolute() ? path : root.resolve(path)).normalize();
                if (!absolute.startsWith(root)) {
                    docker = docker.mount(absolute, absolute.toString(), false);
                }
            }
            if (Boolean.getBoolean("jenesis.verbose")) {
                System.out.println("Launching Java execution within Docker image: " + docker.image());
            }
            return docker.execute(javaArgs);
        }
        String home = System.getProperty("java.home");
        if (home == null) {
            home = System.getenv("JAVA_HOME");
        }
        if (home == null) {
            throw new IllegalStateException("Neither java.home property nor JAVA_HOME environment is set");
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path javaExecutable = Path.of(home, "bin", "java" + (windows ? ".exe" : ""));
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IllegalStateException("No java executable at " + javaExecutable);
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(javaArgs);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    public static void main(String... arguments) {
        try {
            Project project = new Project().resolveProperties();
            int code = new Execute(project).resolveProperties().doExecute(true, arguments);
            if (code != 0) {
                System.exit(code);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed using arguments " + List.of(arguments), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while executing", e);
        }
    }

    private record Candidate(String path,
                             String mainClass,
                             String module,
                             SequencedProperties identity,
                             SequencedMap<String, Path> folders) {
    }
}
