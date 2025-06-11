package build.buildbuddy.module;

import module java.base;
import module java.compiler;
import module jdk.compiler;

import static java.util.Objects.requireNonNull;

public class ModuleInfoParser {

    private final JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();

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
                    String name = requires.getModuleName().toString();
                    if (!name.startsWith("java.") && !name.startsWith("jdk.")) {
                        dependencies.add(name);
                    }
                }
            }
            return new ModuleInfo(module.getName().toString(), dependencies);
        }
        throw new IllegalArgumentException("Expected module-info.java to contain module information");
    }
}
