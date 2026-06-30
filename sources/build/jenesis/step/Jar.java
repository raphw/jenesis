package build.jenesis.step;

import module java.base;
import java.util.jar.Attributes;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class Jar extends JdkProcessBuildStep {

    private final Sort sort;

    public Jar(ProcessHandler.Factory factory, Sort sort) {
        super("jar", factory.apply("jar", "bin/jar"));
        this.sort = sort;
    }

    @Override
    public CompletionStage<List<String>> process(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> commands = new ArrayList<>(List.of(
                "--create",
                "--file",
                Files.createDirectory(context.next().resolve(sort.folder))
                        .resolve(sort.file)
                        .toString(),
                "--date=1980-01-01T00:00:02Z"));
        List<Path> manifestFiles = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path candidate = argument.folder().resolve("manifest.mf");
            if (Files.exists(candidate)) {
                manifestFiles.add(candidate);
            }
        }
        if (!manifestFiles.isEmpty()) {
            Manifest merged = new Manifest();
            for (Path path : manifestFiles) {
                Manifest current;
                try (InputStream in = Files.newInputStream(path)) {
                    current = new Manifest(in);
                }
                mergeAttributes(merged.getMainAttributes(), current.getMainAttributes(), path);
                for (Map.Entry<String, Attributes> entry : current.getEntries().entrySet()) {
                    Attributes target = merged.getEntries().computeIfAbsent(entry.getKey(), _ -> new Attributes());
                    mergeAttributes(target, entry.getValue(), path);
                }
            }
            Path output = context.supplement().resolve("manifest.mf");
            try (OutputStream out = Files.newOutputStream(output)) {
                merged.write(out);
            }
            commands.add("--manifest");
            commands.add(output.toString());
        }
        for (BuildStepArgument argument : arguments.values()) {
            for (String name : sort.folders) {
                Path folder = argument.folder().resolve(name);
                if (Files.exists(folder)) {
                    commands.add("-C");
                    commands.add(folder.toString());
                    commands.add(".");
                }
            }
        }
        return CompletableFuture.completedStage(commands);
    }

    private static void mergeAttributes(Attributes target, Attributes source, Path file) {
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            Object key = entry.getKey(), value = entry.getValue(), existing = target.get(key);
            if (existing == null) {
                target.put(key, value);
            } else if (!existing.equals(value)) {
                throw new IllegalStateException("Conflicting manifest attribute '"
                        + key
                        + "' in "
                        + file
                        + ": '"
                        + existing
                        + "' vs '"
                        + value
                        + "'");
            }
        }
    }

    public enum Sort {

        CLASSES("classes.jar", BuildStep.ARTIFACTS, BuildStep.CLASSES, BuildStep.RESOURCES),
        SOURCES("sources.jar", BuildStep.SOURCES, BuildStep.SOURCES, BuildStep.RESOURCES),
        JAVADOC("javadoc.jar", BuildStep.DOCUMENTATION, Javadoc.JAVADOC);

        final String file;
        final String folder;
        final List<String> folders;

        Sort(String file, String folder, String... folders) {
            this.file = file;
            this.folder = folder;
            this.folders = List.of(folders);
        }

        public String getFile() {
            return file;
        }
    }
}
