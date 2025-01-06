package build.buildbuddy.module;

import build.buildbuddy.Identification;
import build.buildbuddy.Identifier;
import build.buildbuddy.step.Bind;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.ModuleTree;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

import static java.util.Objects.requireNonNull;

public class ModuleInfoIdentifier implements Identifier {

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    @Override
    public Optional<Identification> identify(Path folder) throws IOException {
        Path moduleInfo = folder.resolve(Bind.SOURCES + "module-info.java");
        if (!Files.exists(moduleInfo)) {
            return Optional.empty();
        }
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
            ModuleTree module = unit.getModule();
            SequencedMap<String, String> dependencies = new LinkedHashMap<>();
            for (DirectiveTree directive : requireNonNull(module).getDirectives()) {
                if (directive instanceof RequiresTree requires) {
                    if (!requires.isStatic()) {
                        String name = requires.getModuleName().toString();
                        if (!name.startsWith("java.") && !name.startsWith("jdk.")) {
                            dependencies.put(name, "");
                        }
                    }
                }
            }
            return Optional.of(new Identification(module.getName().toString(), dependencies));
        }
        throw new IllegalArgumentException("Expected module-info.java to contain module information");
    }
}
