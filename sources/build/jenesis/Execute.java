package build.jenesis;

import module java.base;

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
        String targetSegment = module != null ? "module-" + BuildExecutorModule.encode(module) : null;
        SequencedMap<String, Path> outputs = module != null
                ? project.build("+" + module)
                : project.build(Project.BUILD);
        SequencedMap<String, Candidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : outputs.entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith("/manifests")) {
                continue;
            }
            int identifierIndex = key.indexOf("/identifier/");
            if (identifierIndex < 0) {
                continue;
            }
            int lastSlash = key.lastIndexOf('/', key.length() - "/manifests".length() - 1);
            String moduleSegment = key.substring(lastSlash + 1, key.length() - "/manifests".length());
            if (!moduleSegment.startsWith("module-")) {
                continue;
            }
            if (targetSegment != null && !targetSegment.equals(moduleSegment)) {
                continue;
            }
            Path moduleFile = entry.getValue().resolve(BuildStep.MODULE);
            if (!Files.isRegularFile(moduleFile)) {
                continue;
            }
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(moduleFile)) {
                properties.load(reader);
            }
            String main;
            if (mainClass != null) {
                main = mainClass;
            } else {
                main = properties.getProperty("main");
                if (main == null || main.isEmpty()) {
                    continue;
                }
            }
            String path = properties.getProperty("path", "");
            String composeBase = key.substring(0, identifierIndex) + "/compose/module/" + moduleSegment;
            candidates.put(moduleSegment, new Candidate(path, main, properties.getProperty("module"), composeBase));
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
        Path assignFolder = outputs.get(candidate.composeBase + "/assign");
        if (assignFolder == null) {
            throw new IllegalStateException("Did not find assign output for module: "
                    + (candidate.path.isEmpty() ? "<root>" : candidate.path));
        }
        Path identityFile = assignFolder.resolve(BuildStep.IDENTITY);
        if (!Files.isRegularFile(identityFile)) {
            throw new IllegalStateException("Missing identity properties at: " + identityFile);
        }
        Properties identity = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(identityFile)) {
            identity.load(reader);
        }
        Path classesJar = null;
        for (String name : identity.stringPropertyNames()) {
            String value = identity.getProperty(name);
            if (value != null && value.endsWith("classes.jar")) {
                classesJar = assignFolder.resolve(value).normalize();
                break;
            }
        }
        if (classesJar == null || !Files.isRegularFile(classesJar)) {
            throw new IllegalStateException("Did not find classes.jar for module: "
                    + (candidate.path.isEmpty() ? "<root>" : candidate.path));
        }
        List<String> jars = new ArrayList<>();
        jars.add(classesJar.toString());
        Path runtimeArtifacts = outputs.get(candidate.composeBase + "/runtime/dependencies/artifacts");
        if (runtimeArtifacts != null) {
            Path libraries = runtimeArtifacts.resolve("artifacts");
            if (Files.isDirectory(libraries)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(libraries)) {
                    for (Path file : stream) {
                        jars.add(file.toString());
                    }
                }
            }
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
        if (candidate.module != null) {
            command.add("--module-path");
            command.add(String.join(File.pathSeparator, jars));
            command.add("-m");
            command.add(candidate.module + "/" + candidate.mainClass);
        } else {
            command.add("-cp");
            command.add(String.join(File.pathSeparator, jars));
            command.add(candidate.mainClass);
        }
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    public static void main(String... arguments) {
        try {
            int code = new Execute(new Project().resolveProperties()).resolveProperties().execute(arguments);
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

    private record Candidate(String path, String mainClass, String module, String composeBase) {
    }
}
