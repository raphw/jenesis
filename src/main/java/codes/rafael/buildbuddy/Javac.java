package codes.rafael.buildbuddy;

import javax.tools.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Javac implements BuildStep {

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    @Override
    public CompletionStage<String> apply(Executor executor,
                                         Path previous,
                                         Path target,
                                         Map<String, BuildResult> dependencies) throws IOException {
        List<? extends JavaFileObject> files = dependencies.values().stream().flatMap(result -> result.files().keySet().stream()
                .filter(file -> file.getFileName().toString().endsWith(".java"))
                .map(file -> new SimpleJavaFileObject(result.root().resolve(file).toUri(), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public InputStream openInputStream() throws IOException {
                        return Files.newInputStream(result.root().resolve(file));
                    }

                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                        try (InputStream inputStream = Files.newInputStream(result.root().resolve(file))) {
                            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                }))
                .toList();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(Files.createDirectories(target).toFile()));
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null, null, files);
        if (!task.call()) {
            throw new IllegalStateException();
        }
        return CompletableFuture.completedStage("Compiled " + files + " files");
    }
}
