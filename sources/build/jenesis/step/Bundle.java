package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.PathPlacement;
import build.jenesis.SequencedProperties;

public class Bundle implements BuildStep {

    public static final String BUNDLE = "bundle/";

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        String mainClass = null, mainModule = null;
        for (BuildStepArgument argument : arguments.values()) {
            Path properties = argument.folder().resolve("launcher.properties");
            if (!Files.isRegularFile(properties)) {
                continue;
            }
            SequencedProperties launcher = SequencedProperties.ofFiles(properties);
            if (mainClass == null) {
                mainClass = launcher.getProperty("mainClass");
            }
            if (mainModule == null) {
                mainModule = launcher.getProperty("mainModule");
            }
        }
        if (mainClass == null) {
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
        SequencedMap<String, Path> jars = new TreeMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path folder = argument.folder().resolve(BuildStep.ARTIFACTS);
            if (Files.exists(folder)) {
                Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".jar")) {
                            jars.putIfAbsent(name, file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            for (Path file : Dependencies.select(argument.folder(), "runtime")) {
                jars.putIfAbsent(file.getFileName().toString(), file);
            }
        }
        if (jars.isEmpty()) {
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
        SequencedMap<String, Path> classpath = new LinkedHashMap<>(), modulepath = new LinkedHashMap<>();
        boolean hasAutomaticModules = false;
        for (Map.Entry<String, Path> entry : jars.entrySet()) {
            if (mainModule != null) {
                ModuleDescriptor descriptor = PathPlacement.moduleDescriptor(entry.getValue());
                (descriptor != null ? modulepath : classpath).put(entry.getKey(), entry.getValue());
                hasAutomaticModules |= descriptor != null && descriptor.isAutomatic();
            } else {
                classpath.put(entry.getKey(), entry.getValue());
            }
        }
        SequencedProperties application = new SequencedProperties();
        application.setProperty("mainClass", mainClass);
        if (mainModule != null) {
            application.setProperty("mainModule", mainModule);
        }
        if (hasAutomaticModules) {
            application.setProperty("addModules", "ALL-MODULE-PATH");
        }
        Path descriptor = context.supplement().resolve("application.properties");
        application.store(descriptor);
        Path zip = Files.createDirectory(context.next().resolve(BUNDLE)).resolve("bundle.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeEntry(out, "application.properties", descriptor);
            for (Map.Entry<String, Path> entry : classpath.entrySet()) {
                writeEntry(out, "classpath/" + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Path> entry : modulepath.entrySet()) {
                writeEntry(out, "modulepath/" + entry.getKey(), entry.getValue());
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static void writeEntry(ZipOutputStream out, String name, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        out.putNextEntry(entry);
        Files.copy(file, out);
        out.closeEntry();
    }
}
