package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Inventory implements BuildStep {

    public static final String INVENTORY = "inventory.properties";

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        String path = null;
        String mainClass = null;
        String module = null;
        Path mainArtifact = null;
        SequencedSet<Path> dependencies = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path moduleFile = argument.folder().resolve(MODULE);
            if (Files.isRegularFile(moduleFile)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(moduleFile)) {
                    properties.load(reader);
                }
                if (path == null) {
                    path = properties.getProperty("path");
                }
                if (mainClass == null) {
                    mainClass = properties.getProperty("main");
                }
                if (module == null) {
                    module = properties.getProperty("module");
                }
            }
            Path identityFile = argument.folder().resolve(IDENTITY);
            if (mainArtifact == null && Files.isRegularFile(identityFile)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(identityFile)) {
                    properties.load(reader);
                }
                boolean complete = true;
                for (String name : properties.stringPropertyNames()) {
                    if (properties.getProperty(name).isEmpty()) {
                        complete = false;
                        break;
                    }
                }
                if (complete) {
                    for (String name : properties.stringPropertyNames()) {
                        Path resolved = argument.folder().resolve(properties.getProperty(name)).normalize();
                        if (Files.isRegularFile(resolved)) {
                            mainArtifact = resolved;
                            break;
                        }
                    }
                }
            }
            Path artifactsDir = argument.folder().resolve(ARTIFACTS);
            if (Files.isDirectory(artifactsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifactsDir)) {
                    for (Path file : stream) {
                        dependencies.add(file);
                    }
                }
            }
        }
        String prefix = (path == null || path.isEmpty()) ? "" : path + ".";
        Properties inventory = new SequencedProperties();
        SequencedSet<Path> runtime = new LinkedHashSet<>();
        if (mainArtifact != null) {
            runtime.add(mainArtifact);
        }
        runtime.addAll(dependencies);
        if (!runtime.isEmpty()) {
            inventory.setProperty(prefix + "runtime", runtime.stream()
                    .map(file -> context.next().relativize(file).toString().replace(File.separatorChar, '/'))
                    .collect(Collectors.joining(",")));
        }
        if (mainClass != null) {
            inventory.setProperty(prefix + "mainClass", mainClass);
        }
        if (module != null) {
            inventory.setProperty(prefix + "module", module);
        }
        if (!inventory.isEmpty()) {
            try (Writer writer = Files.newBufferedWriter(context.next().resolve(INVENTORY))) {
                inventory.store(writer, null);
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
