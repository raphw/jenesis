package build.buildbuddy.steps;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
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
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public class ModuleDependencies implements BuildStep {

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        List<String> modules = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path moduleInfo = argument.folder().resolve("module-info.java");
            if (Files.exists(moduleInfo)) {
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
                            modules.add(requires.getModuleName().toString());
                        }
                    }
                }
            }
        }
        // TODO: modules to dependencies: download task for modules file.
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
