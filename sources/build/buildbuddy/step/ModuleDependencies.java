package build.buildbuddy.step;

import build.buildbuddy.*;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.util.JavacTask;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class ModuleDependencies implements BuildStep {

    public static final URI MODULES_PROPERTIES = URI.create("https://raw.githubusercontent.com/" +
            "sormuras/modules/refs/heads/main/" +
            "com.github.sormuras.modules/com/github/sormuras/modules/modules.properties");
    public static final String MODULES = "modules/";

    private final Function<String, String> resolver;
    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    public ModuleDependencies() {
        resolver = value -> {
            if (value.startsWith("https://repo.maven.apache.org/maven2/") && value.endsWith(".jar")) {
                List<String> elements = Arrays.asList(value
                        .substring("https://repo.maven.apache.org/maven2/".length())
                        .split("/"));
                return "maven/"
                        + String.join(".", elements.subList(0, elements.size() - 3))
                        + "/" + elements.get(elements.size() - 3)
                        + "/" + elements.get(elements.size() - 2);
            } else if (value.startsWith("file://")) {
                return "file/" + value.substring("file://".length());
            } else {
                throw new IllegalArgumentException();
            }
        };
    }

    public ModuleDependencies(Function<String, String> resolver) {
        this.resolver = resolver;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        Map<String, String> references = new HashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            Path modules = entry.getValue().folder().resolve(MODULES + "modules.properties");
            if (Files.exists(modules)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(modules)) {
                    properties.load(reader);
                }
                for (String module : properties.stringPropertyNames()) {
                    references.put(module, resolver.apply(properties.getProperty(module)));
                }
            }
        }
        Path dependencies = Files.createDirectory(context.next().resolve(PropertyDependencies.DEPENDENCIES));
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            Path moduleInfo = entry.getValue().folder().resolve(Bind.SOURCES + "module-info.java");
            if (Files.exists(moduleInfo)) {
                Properties properties = new SequencedProperties();
                JavacTask javac = (JavacTask) compiler.getTask(new PrintWriter(Writer.nullWriter()),
                        compiler.getStandardFileManager(null, null, null),
                        null,
                        null,
                        null,
                        List.of(new SimpleJavaFileObject(moduleInfo.toUri(), JavaFileObject.Kind.SOURCE) {
                            @Override
                            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                                return Files.readString(moduleInfo);
                            }
                        }));
                for (CompilationUnitTree unit : javac.parse()) {
                    for (DirectiveTree directive : requireNonNull(unit.getModule()).getDirectives()) {
                        if (directive instanceof RequiresTree requires) {
                            properties.put(Objects.requireNonNull(
                                    references.get(requires.getModuleName().toString()),
                                    "Unknown module: " + requires.getModuleName().toString()), "");
                        }
                    }
                }
                try (Writer writer = Files.newBufferedWriter(dependencies.resolve(entry.getKey() + ".properties"))) {
                    properties.store(writer, null);
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
