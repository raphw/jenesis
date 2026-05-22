package build.jenesis.module;

import module java.base;
import module jdk.compiler;
import javax.tools.ToolProvider;

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
            String release = null;
            String name = null;
            String description = null;
            String testOf = null;
            String main = null;
            DocCommentTree docComment = docTrees.getDocCommentTree(TreePath.getPath(unit, module));
            if (docComment != null) {
                String summary = docComment.getFirstSentence().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining())
                        .trim();
                if (!summary.isEmpty()) {
                    name = summary.endsWith(".")
                            ? summary.substring(0, summary.length() - 1)
                            : summary;
                }
                String body = docComment.getBody().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining())
                        .trim();
                if (!body.isEmpty()) {
                    description = body;
                }
                for (DocTree tag : docComment.getBlockTags()) {
                    if (tag instanceof UnknownBlockTagTree unknown) {
                        String content = unknown.getContent().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining())
                                .trim();
                        switch (unknown.getTagName()) {
                            case "jenesis.pin" -> {
                                int split = content.indexOf(' ');
                                if (split < 1 || split == content.length() - 1) {
                                    continue;
                                }
                                String dependency = content.substring(0, split).trim();
                                String version = content.substring(split + 1).trim();
                                if (dependency.startsWith("java.") || dependency.startsWith("jdk.") || dependency.isEmpty() || version.isEmpty()) {
                                    continue;
                                }
                                versions.put(dependency, version);
                            }
                            case "jenesis.release" -> {
                                if (!content.isEmpty()) {
                                    release = content;
                                }
                            }
                            case "jenesis.test" -> testOf = content;
                            case "jenesis.main" -> {
                                if (!content.isEmpty()) {
                                    main = content;
                                }
                            }
                        }
                    }
                }
            }
            return new ModuleInfo(module.getName().toString(),
                    release,
                    name,
                    description,
                    testOf,
                    main,
                    dependencies,
                    runtimeDependencies,
                    versions);
        }
        throw new IllegalArgumentException("Expected module-info.java to contain module information");
    }
}
