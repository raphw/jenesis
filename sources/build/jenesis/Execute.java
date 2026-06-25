package build.jenesis;

import module java.base;
import build.jenesis.docker.DockerizedJava;
import build.jenesis.step.Inventory;

public record Execute(Project project, String mainClass, String module) {

    public Execute(Project project) {
        this(project,
                System.getProperty("jenesis.execute.mainClass"),
                System.getProperty("jenesis.execute.module"));
    }

    public Execute mainClass(String mainClass) {
        return new Execute(project, mainClass, module);
    }

    public Execute module(String module) {
        return new Execute(project, mainClass, module);
    }

    public int execute(String... arguments) throws IOException, InterruptedException {
        return doExecute(false, arguments);
    }

    private int doExecute(boolean mainMethod, String... arguments) throws IOException, InterruptedException {
        String selector = module != null ? "+" + module.replace('/', '+') : Project.BUILD;
        SequencedMap<String, Path> outputs = mainMethod ? project.doMain(selector) : project.build(selector);
        SequencedProperties merged = new SequencedProperties();
        SequencedMap<String, Path> sourceByPrefix = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : outputs.entrySet()) {
            Path inventory = entry.getValue().resolve(Inventory.INVENTORY);
            if (!Files.isRegularFile(inventory)) {
                continue;
            }
            SequencedProperties loaded = SequencedProperties.ofFiles(inventory);
            for (String key : loaded.stringPropertyNames()) {
                merged.setProperty(key, loaded.getProperty(key));
                int dot = key.indexOf('.');
                if (dot > 0) {
                    sourceByPrefix.put(key.substring(0, dot), entry.getValue());
                }
            }
        }
        String selectedPrefix = module == null
                ? null
                : (module.isEmpty() ? "module" : "module-" + module.replace('+', '/'));
        if (selectedPrefix != null && !sourceByPrefix.containsKey(selectedPrefix)) {
            throw new IllegalStateException("No module at path: " + (module.isEmpty() ? "<root>" : module));
        }
        SequencedMap<String, Candidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : sourceByPrefix.entrySet()) {
            String prefix = entry.getKey();
            if (selectedPrefix != null && !selectedPrefix.equals(prefix)) {
                continue;
            }
            String prefixDot = prefix + ".";
            String resolvedMainClass = mainClass != null ? mainClass : merged.getProperty(prefixDot + "mainClass");
            if (resolvedMainClass == null) {
                continue;
            }
            String userPath = "module".equals(prefix) ? "" : prefix.substring("module-".length());
            candidates.put(prefix, new Candidate(userPath,
                    resolvedMainClass,
                    merged.getProperty(prefixDot + "module"),
                    Inventory.paths(merged, entry.getValue(), prefixDot + "runtime"),
                    entry.getValue()));
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
        if (candidate.runtime == null || candidate.runtime.isEmpty()) {
            throw new IllegalStateException("No runtime artifacts for module: "
                    + (candidate.path.isEmpty() ? "<root>" : candidate.path));
        }
        List<String> jars = new ArrayList<>();
        for (Path resolved : candidate.runtime) {
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalStateException("Missing runtime artifact for module "
                        + (candidate.path.isEmpty() ? "<root>" : candidate.path)
                        + ": " + resolved);
            }
            jars.add(resolved.toString());
        }
        List<String> javaArgs = new ArrayList<>();
        if (candidate.module != null) {
            boolean selfContainedModuleGraph = true;
            List<String> modulePath = new ArrayList<>(), classPath = new ArrayList<>();
            for (String jar : jars) {
                ModuleDescriptor descriptor = PathPlacement.moduleDescriptor(Path.of(jar));
                (descriptor != null ? modulePath : classPath).add(jar);
                selfContainedModuleGraph &= descriptor != null && !descriptor.isAutomatic();
            }
            javaArgs.add("--module-path");
            javaArgs.add(String.join(File.pathSeparator, modulePath));
            if (!classPath.isEmpty()) {
                javaArgs.add("--class-path");
                javaArgs.add(String.join(File.pathSeparator, classPath));
            }
            if (!selfContainedModuleGraph) {
                javaArgs.add("--add-modules");
                javaArgs.add("ALL-MODULE-PATH");
            }
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
            for (Path path : List.of(project.target(), project.artifacts())) {
                Path absolute = (path.isAbsolute() ? path : root.resolve(path)).normalize();
                if (!absolute.startsWith(root)) {
                    docker = docker.mount(absolute, absolute.toString(), false);
                }
            }
            docker = docker.mounts(System.getProperty("jenesis.execute.docker.mount"), root, true);
            docker = docker.mounts(System.getProperty("jenesis.execute.docker.mountWritable"), root, false);
            docker = docker.envs(System.getProperty("jenesis.execute.docker.env"));
            if (Boolean.parseBoolean(System.getProperty("jenesis.print.docker", "true"))) {
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
            Project.loadJenesisProperties(Path.of(System.getProperty("jenesis.project.root", ".")));
            Project project = new Project();
            int code = new Execute(project).doExecute(true, arguments);
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
                             List<Path> runtime,
                             Path folder) {
    }
}
