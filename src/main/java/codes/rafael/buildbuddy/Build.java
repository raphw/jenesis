package codes.rafael.buildbuddy;
import javax.tools.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Build {
    public static void main(String... args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<JavaFileObject> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("./src"), "*")) {
            stream.forEach(path -> {
                if (!path.getFileName().toString().endsWith(".java")) {
                    return;
                }
                files.add(new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public InputStream openInputStream() throws IOException {
                        return Files.newInputStream(path);
                    }
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                        try (InputStream inputStream = Files.newInputStream(path)) {
                            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                });
            });
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(Files.createDirectories(Paths.get("./build")).toFile()));
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null, null, files);
        boolean success = task.call();
        System.out.println("Compiled: " + success);
    }
}
