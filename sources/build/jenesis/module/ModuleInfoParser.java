package build.jenesis.module;

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
        DocTrees docTrees = DocTrees.instance(javac);
        for (CompilationUnitTree unit : javac.parse()) {
            ModuleTree module = requireNonNull(unit.getModule());
            SequencedSet<String> dependencies = new LinkedHashSet<>();
            SequencedSet<String> runtimeDependencies = new LinkedHashSet<>();
            for (DirectiveTree directive : module.getDirectives()) {
                if (directive instanceof RequiresTree requires) {
                    String name = requires.getModuleName().toString();
                    if (!name.startsWith("java.") && !name.startsWith("jdk.")) {
                        dependencies.add(name);
                        if (!requires.isStatic()) {
                            runtimeDependencies.add(name);
                        }
                    }
                }
            }
            SequencedMap<String, String> versions = new LinkedHashMap<>();
            DocCommentTree docComment = docTrees.getDocCommentTree(TreePath.getPath(unit, module));
            if (docComment != null) {
                for (DocTree tag : docComment.getBlockTags()) {
                    if (tag instanceof UnknownBlockTagTree unknown && "requires".equals(unknown.getTagName())) {
                        String content = unknown.getContent().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining())
                                .trim();
                        int split = content.indexOf(' ');
                        if (split < 1 || split == content.length() - 1) {
                            continue;
                        }
                        String name = content.substring(0, split).trim();
                        String version = content.substring(split + 1).trim();
                        if (name.startsWith("java.") || name.startsWith("jdk.") || name.isEmpty() || version.isEmpty()) {
                            continue;
                        }
                        versions.put(name, version);
                    }
                }
            }
            return new ModuleInfo(module.getName().toString(), dependencies, runtimeDependencies, versions);
        }
        throw new IllegalArgumentException("Expected module-info.java to contain module information");
    }
}
