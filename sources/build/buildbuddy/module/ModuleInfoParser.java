package build.buildbuddy.module;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import static java.util.Objects.requireNonNull;

public class ModuleInfoParser {

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    public ModuleInfo identify(Path moduleInfo) throws IOException {
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
            SequencedSet<String> dependencies = new LinkedHashSet<>();
            for (DirectiveTree directive : requireNonNull(module).getDirectives()) {
                if (directive instanceof RequiresTree requires) {
                    if (!requires.isStatic()) {
                        String name = requires.getModuleName().toString();
                        if (!name.startsWith("java.") && !name.startsWith("jdk.")) {
                            dependencies.add(name);
                        }
                    }
                }
            }
            return new ModuleInfo(module.getName().toString(), dependencies);
        }
        throw new IllegalArgumentException("Expected module-info.java to contain module information");
    }
}
