package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.PathPlacement;
import build.jenesis.SequencedProperties;
import build.jenesis.module.ModuleInfoParser;

public class Javac extends JdkProcessBuildStep {

    private static final Pattern VERSIONED = Pattern.compile("META-INF/versions/(\\d+)/.+");

    private final boolean includeResources;
    private final PathPlacement pathPlacement;
    private final String group;

    public Javac(ProcessHandler.Factory factory) {
        this(factory.apply("javac", "bin/javac"), true, PathPlacement.INFERRED, "main");
    }

    private Javac(Function<List<String>, ? extends ProcessHandler> factory,
                  boolean includeResources,
                  PathPlacement pathPlacement,
                  String group) {
        super("javac", factory);
        this.includeResources = includeResources;
        this.pathPlacement = pathPlacement;
        this.group = group;
    }

    public static void writeRelease(Path folder, String release) throws IOException {
        if (release == null || release.isEmpty()) {
            return;
        }
        Path target = Files.createDirectories(folder.resolve(ProcessBuildStep.PROCESS));
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("--release", release);
        properties.store(target.resolve("javac.properties"));
    }

    public Javac includeResources(boolean includeResources) {
        return new Javac(factory, includeResources, pathPlacement, group);
    }

    public Javac pathPlacement(PathPlacement pathPlacement) {
        return new Javac(factory, includeResources, pathPlacement, group);
    }

    public Javac group(String group) {
        return new Javac(factory, includeResources, pathPlacement, group);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return hasRelevantChange(arguments, Set.of(".java"), Set.of("javac.properties"));
    }

    public static boolean hasRelevantChange(SequencedMap<String, BuildStepArgument> arguments,
                                            Set<String> sourceExtensions,
                                            Set<String> processProperties) {
        Path sourcesDir = Path.of(SOURCES);
        Path classesDir = Path.of(CLASSES);
        Path artifactsDir = Path.of(ARTIFACTS);
        Path dependencyIndex = Path.of(DEPENDENCIES);
        Set<Path> processFiles = new LinkedHashSet<>();
        for (String name : processProperties) {
            processFiles.add(Path.of(ProcessBuildStep.PROCESS + name));
        }
        for (BuildStepArgument argument : arguments.values()) {
            for (Map.Entry<Path, Checksum> entry : argument.files().entrySet()) {
                if (entry.getValue().status() == ChecksumStatus.RETAINED) {
                    continue;
                }
                Path path = entry.getKey();
                if (processFiles.contains(path)) {
                    return true;
                }
                if (path.startsWith(classesDir)
                        || path.startsWith(artifactsDir)
                        || path.startsWith(dependencyIndex)) {
                    return true;
                }
                if (path.startsWith(sourcesDir)) {
                    Path leaf = path.getFileName();
                    if (leaf != null) {
                        String name = leaf.toString();
                        for (String extension : sourceExtensions) {
                            if (name.endsWith(extension)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        return super.apply(executor, context, arguments).thenComposeAsync(result -> {
            if (!result.next()) {
                return CompletableFuture.completedStage(result);
            }
            try {
                return compileVersioned(executor, context, arguments).thenApply(_ -> result);
            } catch (IOException e) {
                CompletableFuture<BuildStepResult> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }, executor);
    }

    @Override
    public CompletionStage<List<String>> process(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        Path target = Files.createDirectory(context.next().resolve(CLASSES));
        List<String> files = new ArrayList<>(),
                path = new ArrayList<>(),
                processorPath = new ArrayList<>(),
                siblingClasses = new ArrayList<>(),
                commands = new ArrayList<>(List.of("-d", target.toString()));
        for (BuildStepArgument argument : arguments.values()) {
            for (Path jar : Dependencies.select(argument.folder(), group, "compile")) {
                path.add(jar.toString());
            }
            for (Path jar : Dependencies.select(argument.folder(), "plugin", "plugin")) {
                processorPath.add(jar.toString());
            }
            Path sources = argument.folder().resolve(Bind.SOURCES),
                    classes = argument.folder().resolve(CLASSES);
            if (Files.exists(classes)) {
                siblingClasses.add(classes.toString());
            }
            if (Files.exists(sources)) {
                Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(target.resolve(sources.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String name = file.toString();
                        Path relative = sources.relativize(file);
                        if (name.endsWith(".java")) {
                            if (versionOf(relative) == null) {
                                files.add(name);
                            }
                        } else if (includeResources && !BuildStep.underMetaInfVersions(relative)) {
                            BuildStep.linkOrCopy(target.resolve(relative), file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        files.sort(null);
        if (files.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        String moduleInfo = files.stream()
                .filter(file -> file.endsWith(File.separator + "module-info.java"))
                .findFirst()
                .orElse(null);
        boolean module = moduleInfo != null;
        PathPlacement pathPlacement = this.pathPlacement.forModuleInfo(module);
        String patchModule = null;
        if (module && !siblingClasses.isEmpty()) {
            patchModule = new ModuleInfoParser().identify(Path.of(moduleInfo)).coordinate();
        } else {
            path.addAll(siblingClasses);
        }
        if (!path.isEmpty() || patchModule != null || !processorPath.isEmpty()) {
            for (String entry : path) {
                if (entry.indexOf(File.pathSeparatorChar) != -1) {
                    throw new IllegalArgumentException(
                            "Path entry contains separator '" + File.pathSeparator + "': " + entry);
                }
            }
            for (String entry : processorPath) {
                if (entry.indexOf(File.pathSeparatorChar) != -1) {
                    throw new IllegalArgumentException(
                            "Path entry contains separator '" + File.pathSeparator + "': " + entry);
                }
            }
            List<String> modulePath = new ArrayList<>(), classPath = new ArrayList<>();
            for (String entry : path) {
                (pathPlacement.test(Path.of(entry)) ? modulePath : classPath).add(entry);
            }
            StringBuilder args = new StringBuilder();
            if (!modulePath.isEmpty()) {
                args.append("--module-path\n\"")
                        .append(String.join(File.pathSeparator, modulePath).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\"\n");
            }
            if (!classPath.isEmpty()) {
                args.append("--class-path\n\"")
                        .append(String.join(File.pathSeparator, classPath).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\"\n");
            }
            if (patchModule != null) {
                args.append("--patch-module\n\"")
                        .append(patchModule)
                        .append("=")
                        .append(String.join(File.pathSeparator, siblingClasses).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\"\n");
            }
            if (!processorPath.isEmpty()) {
                args.append(pathPlacement.modular() ? "--processor-module-path\n\"" : "--processor-path\n\"")
                        .append(String.join(File.pathSeparator, processorPath).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\"\n");
            }
            Path file = context.supplement().resolve("javac.args");
            Files.writeString(file, args.toString());
            commands.add("@" + file);
        }
        commands.addAll(files);
        return CompletableFuture.completedStage(commands);
    }

    private CompletionStage<Void> compileVersioned(Executor executor,
                                                   BuildStepContext context,
                                                   SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<Integer, List<String>> versionedFiles = new TreeMap<>();
        SequencedMap<Integer, List<String>> versionedRoots = new TreeMap<>();
        List<String> dependencyPath = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path classes = argument.folder().resolve(CLASSES);
            if (Files.exists(classes)) {
                dependencyPath.add(classes.toString());
            }
            for (Path jar : Dependencies.select(argument.folder(), group, "compile")) {
                dependencyPath.add(jar.toString());
            }
            Path sources = argument.folder().resolve(Bind.SOURCES);
            if (Files.exists(sources)) {
                Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.toString();
                        if (!name.endsWith(".java")) {
                            return FileVisitResult.CONTINUE;
                        }
                        Integer release = versionOf(sources.relativize(file));
                        if (release != null) {
                            versionedFiles.computeIfAbsent(release, _ -> new ArrayList<>()).add(name);
                            String root = sources.resolve("META-INF/versions/" + release).toString();
                            List<String> roots = versionedRoots.computeIfAbsent(release, _ -> new ArrayList<>());
                            if (!roots.contains(root)) {
                                roots.add(root);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        dependencyPath.sort(null);
        versionedFiles.values().forEach(versioned -> versioned.sort(null));
        versionedRoots.values().forEach(roots -> roots.sort(null));
        if (versionedFiles.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        Path mainTarget = context.next().resolve(CLASSES);
        Path moduleInfo = mainTarget.resolve("module-info.class");
        String moduleName;
        if (Files.exists(moduleInfo)) {
            try (InputStream in = Files.newInputStream(moduleInfo)) {
                moduleName = ModuleDescriptor.read(in).name();
            }
        } else {
            moduleName = null;
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map.Entry<Integer, List<String>> entry : versionedFiles.entrySet()) {
            int release = entry.getKey();
            List<String> files = entry.getValue();
            List<String> roots = versionedRoots.get(release);
            chain = chain.thenComposeAsync(_ -> {
                try {
                    return runVersioned(executor, context, dependencyPath, moduleName, mainTarget, roots, release, files);
                } catch (IOException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }, executor);
        }
        return chain.thenComposeAsync(_ -> {
            try {
                if (!hasMultiReleaseManifest(arguments)) {
                    Path manifest = context.next().resolve("manifest.mf");
                    if (!Files.exists(manifest)) {
                        Files.writeString(manifest, "Manifest-Version: 1.0\r\nMulti-Release: true\r\n");
                    }
                }
                return CompletableFuture.<Void>completedFuture(null);
            } catch (IOException e) {
                return CompletableFuture.<Void>failedFuture(e);
            }
        }, executor);
    }

    private static boolean hasMultiReleaseManifest(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            Path candidate = argument.folder().resolve("manifest.mf");
            if (!Files.exists(candidate)) {
                continue;
            }
            Manifest manifest;
            try (InputStream in = Files.newInputStream(candidate)) {
                manifest = new Manifest(in);
            }
            if (manifest.getMainAttributes().getValue("Multi-Release") != null) {
                return true;
            }
        }
        return false;
    }

    private CompletionStage<Void> runVersioned(Executor executor,
                                               BuildStepContext context,
                                               List<String> dependencyPath,
                                               String moduleName,
                                               Path mainTarget,
                                               List<String> versionedRoots,
                                               int release,
                                               List<String> files) throws IOException {
        Path target = Files.createDirectories(context.next()
                .resolve(CLASSES + "META-INF/versions/" + release));
        List<String> commands = new ArrayList<>(List.of(
                "-d", target.toString(),
                "--release", Integer.toString(release)));
        List<String> classPath = new ArrayList<>(), modulePath = new ArrayList<>(), patchModule = new ArrayList<>();
        if (moduleName != null) {
            if (Files.exists(mainTarget)) {
                modulePath.add(mainTarget.toString());
            }
            for (String entry : dependencyPath) {
                (pathPlacement.test(Path.of(entry)) ? modulePath : classPath).add(entry);
            }
            patchModule.addAll(versionedRoots);
        } else {
            if (Files.exists(mainTarget)) {
                classPath.add(mainTarget.toString());
            }
            classPath.addAll(dependencyPath);
        }
        for (List<String> entries : List.of(classPath, modulePath, patchModule)) {
            for (String entry : entries) {
                if (entry.indexOf(File.pathSeparatorChar) != -1) {
                    throw new IllegalArgumentException(
                            "Path entry contains separator '" + File.pathSeparator + "': " + entry);
                }
            }
        }
        StringBuilder args = new StringBuilder();
        if (!modulePath.isEmpty()) {
            String escaped = String.join(File.pathSeparator, modulePath)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            args.append("--module-path\n\"").append(escaped).append("\"\n");
        }
        if (!classPath.isEmpty()) {
            String escaped = String.join(File.pathSeparator, classPath)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            args.append("--class-path\n\"").append(escaped).append("\"\n");
        }
        if (!patchModule.isEmpty()) {
            String escaped = String.join(File.pathSeparator, patchModule)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            args.append("--patch-module\n\"")
                    .append(moduleName)
                    .append("=")
                    .append(escaped)
                    .append("\"\n");
        }
        if (!args.isEmpty()) {
            Path argFile = context.supplement().resolve("javac-" + release + ".args");
            Files.writeString(argFile, args.toString());
            commands.add("@" + argFile);
        }
        commands.addAll(files);
        Path output = context.supplement().resolve("output-" + release);
        Path error = context.supplement().resolve("error-" + release);
        ProcessHandler handler = factory.apply(commands);
        Files.writeString(context.supplement().resolve("command-" + release), String.join(" ", handler.commands()));
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                int exitCode = handler.execute(output, error);
                if (exitCode == 0) {
                    future.complete(null);
                } else {
                    String outputString = Files.exists(output) ? Files.readString(output) : "";
                    String errorString = Files.exists(error) ? Files.readString(error) : "";
                    future.completeExceptionally(new IllegalStateException(
                            "Unexpected exit code: " + exitCode + " (multi-release " + release + ")\n"
                                    + "To reproduce, execute:\n " + String.join(" ", handler.commands())
                                    + (outputString.isBlank() ? "" : ("\n\nOutput:\n" + outputString))
                                    + (errorString.isBlank() ? "" : ("\n\nError:\n" + errorString))));
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static Integer versionOf(Path relative) {
        String normalized = relative.toString().replace(File.separatorChar, '/');
        Matcher matcher = VERSIONED.matcher(normalized);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }
}
