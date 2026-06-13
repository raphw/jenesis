package build.jenesis.module;

import module java.base;
import module jdk.compiler;
import build.jenesis.Platform;
import javax.tools.ToolProvider;

import static java.util.Objects.requireNonNull;

public class ModuleInfoParser {

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    private final String group;

    public ModuleInfoParser() {
        this("main");
    }

    public ModuleInfoParser(String group) {
        this.group = group;
    }

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
            SequencedMap<String, SequencedMap<String, String>> variants = new LinkedHashMap<>();
            SequencedMap<String, String> plugins = new LinkedHashMap<>();
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
                                String pin = content.replaceAll("\\s+", " ");
                                int split = pin.indexOf(' ');
                                if (split < 1 || split == pin.length() - 1) {
                                    continue;
                                }
                                String token = pin.substring(0, split);
                                String version = pin.substring(split + 1).trim();
                                String guard = null;
                                if (version.endsWith("]")) {
                                    int bracket = version.lastIndexOf('[');
                                    if (bracket < 0) {
                                        throw new IllegalArgumentException("Malformed @jenesis.pin guard '"
                                                + version
                                                + "': expected <value> [<token>,<token>...]");
                                    }
                                    guard = Platform.of(
                                            version.substring(bracket + 1, version.length() - 1)).canonical();
                                    version = version.substring(0, bracket).trim();
                                }
                                if (token.isEmpty() || version.isEmpty()
                                        || token.startsWith("java.") || token.startsWith("jdk.")) {
                                    continue;
                                }
                                String key;
                                int firstSlash = token.indexOf('/');
                                int secondSlash = firstSlash < 0 ? -1 : token.indexOf('/', firstSlash + 1);
                                if (firstSlash < 0) {
                                    key = group + "/module/" + token;
                                } else if (secondSlash < 0) {
                                    if (firstSlash < 1 || firstSlash == token.length() - 1) {
                                        throw new IllegalArgumentException("Malformed @jenesis.pin token '"
                                                + token
                                                + "': expected <module>, <groupId>/<artifactId>,"
                                                + " or <group>/<repository>/<coordinate>");
                                    }
                                    key = group + "/maven/" + token;
                                } else {
                                    if (firstSlash < 1 || secondSlash == firstSlash + 1
                                            || secondSlash == token.length() - 1) {
                                        throw new IllegalArgumentException("Malformed @jenesis.pin token '"
                                                + token
                                                + "': expected <module>, <groupId>/<artifactId>,"
                                                + " or <group>/<repository>/<coordinate>");
                                    }
                                    key = token;
                                }
                                if (guard == null) {
                                    versions.put(key, version);
                                } else {
                                    variants.computeIfAbsent(key, _ -> new LinkedHashMap<>()).put(guard, version);
                                }
                            }
                            case "jenesis.plugin" -> {
                                String trimmed = content.trim();
                                int space = trimmed.indexOf(' ');
                                String group, token;
                                if (space > 0 && trimmed.substring(0, space).indexOf('/') < 0) {
                                    group = trimmed.substring(0, space).trim();
                                    token = trimmed.substring(space + 1).trim();
                                } else {
                                    group = "plugin";
                                    token = trimmed;
                                }
                                if (token.isEmpty()) {
                                    continue;
                                }
                                plugins.put(token.indexOf('/') < 0 ? "module/" + token : token, group);
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
                    plugins,
                    versions,
                    variants);
        }
        throw new IllegalArgumentException("Expected module-info.java to contain module information");
    }
}
