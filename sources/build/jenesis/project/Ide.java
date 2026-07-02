package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;

public class Ide implements BuildStep {

    public static final String IDEA = "idea", VSCODE = "vscode", ECLIPSE = "eclipse";

    private static final List<String> MAIN_SOURCES = List.of(
            "src/main/java", "src/main/kotlin", "src/main/scala", "src/main/groovy", "sources", "src");
    private static final List<String> TEST_SOURCES = List.of(
            "src/test/java", "src/test/kotlin", "src/test/scala", "src/test/groovy", "tests", "test");

    private final Path root;
    private final String tool;

    public Ide(Path root, String tool) {
        this.root = root;
        this.tool = tool;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return true;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Module> modules = read(arguments.values(), base);
        switch (tool) {
            case IDEA -> idea(modules, base);
            case ECLIPSE -> eclipse(modules);
            case VSCODE -> vscode(modules, base);
            default -> throw new IllegalArgumentException("Unknown IDE tool: " + tool);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private List<Module> read(Collection<BuildStepArgument> arguments, Path base) throws IOException {
        List<Raw> raws = new ArrayList<>();
        SequencedMap<String, String> identities = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments) {
            Path inventoryFile = argument.folder().resolve(Inventory.INVENTORY);
            if (!Files.isRegularFile(inventoryFile)) {
                continue;
            }
            SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
            String prefix = prefix(inventory);
            if (prefix == null) {
                continue;
            }
            String path = inventory.getProperty(prefix + ".path");
            if (path == null) {
                continue;
            }
            Path content = path.isEmpty() ? base : base.resolve(path).normalize();
            String module = inventory.getProperty(prefix + ".module");
            String name = module == null || module.isEmpty() ? name(path, base) : module;
            boolean test = inventory.getProperty(prefix + ".test") != null;
            List<String> coordinates = new ArrayList<>();
            List<Path> jars = new ArrayList<>();
            for (int index = 0; ; index++) {
                String value = inventory.getProperty(prefix + ".dependency." + index);
                if (value == null) {
                    break;
                }
                String[] parts = value.split(" ");
                coordinates.add(parts[0]);
                jars.add(argument.folder().resolve(parts[1]).toAbsolutePath().normalize());
            }
            for (String key : inventory.stringPropertyNames()) {
                if (key.startsWith(prefix + ".identity.")) {
                    String coordinate = inventory.getProperty(key);
                    if (!coordinate.contains("/pom/")) {
                        identities.putIfAbsent(coordinate, name);
                    }
                }
            }
            raws.add(new Raw(name, content, test, coordinates, jars));
        }
        List<Module> modules = new ArrayList<>();
        for (Raw raw : raws) {
            SequencedSet<Path> libraries = new LinkedHashSet<>();
            SequencedSet<String> moduleDependencies = new LinkedHashSet<>();
            for (int index = 0; index < raw.coordinates().size(); index++) {
                String internal = identities.get(raw.coordinates().get(index));
                if (internal == null) {
                    libraries.add(raw.jars().get(index));
                } else if (!internal.equals(raw.name())) {
                    moduleDependencies.add(internal);
                }
            }
            List<Path> mainSources = new ArrayList<>();
            List<Path> testSources = new ArrayList<>();
            sources(raw.content(), raw.test(), mainSources, testSources);
            modules.add(new Module(raw.name(),
                    raw.content(),
                    mainSources,
                    testSources,
                    new ArrayList<>(libraries),
                    new ArrayList<>(moduleDependencies)));
        }
        return modules;
    }

    private static void sources(Path content, boolean test, List<Path> mainSources, List<Path> testSources) {
        for (String candidate : MAIN_SOURCES) {
            Path directory = content.resolve(candidate);
            if (Files.isDirectory(directory)) {
                (test ? testSources : mainSources).add(directory);
            }
        }
        for (String candidate : TEST_SOURCES) {
            Path directory = content.resolve(candidate);
            if (Files.isDirectory(directory)) {
                testSources.add(directory);
            }
        }
        if (test && testSources.isEmpty()) {
            testSources.add(content);
        } else if (!test && mainSources.isEmpty()) {
            mainSources.add(content);
        }
    }

    private static String name(String path, Path base) {
        if (path.isEmpty()) {
            Path name = base.getFileName();
            return name == null ? "root" : name.toString();
        }
        return path.replace('/', '.').replace('\\', '.');
    }

    private static String prefix(SequencedProperties inventory) {
        for (String key : inventory.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                return key.substring(0, dot);
            }
        }
        return null;
    }

    private void idea(List<Module> modules, Path base) throws IOException {
        Path folder = Files.createDirectories(base.resolve(".idea"));
        List<String> entries = new ArrayList<>();
        for (Module module : modules) {
            Path file = module.content().resolve(module.name() + ".iml");
            Files.writeString(file, module(module, base));
            String relative = base.relativize(file).toString().replace(File.separatorChar, '/');
            entries.add("      <module fileurl=\"file://$PROJECT_DIR$/" + relative
                    + "\" filepath=\"$PROJECT_DIR$/" + relative + "\"/>");
        }
        Files.writeString(folder.resolve("modules.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ProjectModuleManager">
                    <modules>
                %s
                    </modules>
                  </component>
                </project>
                """.formatted(String.join("\n", entries)));
        int feature = Runtime.version().feature();
        Files.writeString(folder.resolve("misc.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ProjectRootManager" version="2" languageLevel="JDK_%1$d" project-jdk-name="%1$d" project-jdk-type="JavaSDK">
                    <output url="file://$PROJECT_DIR$/out"/>
                  </component>
                </project>
                """.formatted(feature));
    }

    private static String module(Module module, Path base) {
        StringBuilder content = new StringBuilder();
        content.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        content.append("<module type=\"JAVA_MODULE\" version=\"4\">\n");
        content.append("  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"true\">\n");
        content.append("    <exclude-output/>\n");
        content.append("    <content url=\"file://$MODULE_DIR$\">\n");
        for (Path source : module.mainSources()) {
            content.append("      <sourceFolder url=\"file://$MODULE_DIR$")
                    .append(relative(module.content(), source))
                    .append("\" isTestSource=\"false\"/>\n");
        }
        for (Path source : module.testSources()) {
            content.append("      <sourceFolder url=\"file://$MODULE_DIR$")
                    .append(relative(module.content(), source))
                    .append("\" isTestSource=\"true\"/>\n");
        }
        content.append("    </content>\n");
        content.append("    <orderEntry type=\"inheritedJdk\"/>\n");
        content.append("    <orderEntry type=\"sourceFolder\" forTests=\"false\"/>\n");
        for (String dependency : module.moduleDependencies()) {
            content.append("    <orderEntry type=\"module\" module-name=\"")
                    .append(escape(dependency))
                    .append("\"/>\n");
        }
        for (Path library : module.libraries()) {
            content.append("    <orderEntry type=\"module-library\">\n");
            content.append("      <library>\n");
            content.append("        <CLASSES>\n");
            content.append("          <root url=\"jar://").append(escape(libraryUrl(base, library))).append("!/\"/>\n");
            content.append("        </CLASSES>\n");
            content.append("        <JAVADOC/>\n");
            content.append("        <SOURCES/>\n");
            content.append("      </library>\n");
            content.append("    </orderEntry>\n");
        }
        content.append("  </component>\n");
        content.append("</module>\n");
        return content.toString();
    }

    private static String relative(Path content, Path source) {
        String relative = content.relativize(source).toString().replace(File.separatorChar, '/');
        return relative.isEmpty() ? "" : "/" + relative;
    }

    private static String libraryUrl(Path base, Path library) {
        return library.startsWith(base)
                ? "$PROJECT_DIR$/" + base.relativize(library).toString().replace(File.separatorChar, '/')
                : library.toString().replace(File.separatorChar, '/');
    }

    private void eclipse(List<Module> modules) throws IOException {
        for (Module module : modules) {
            Files.writeString(module.content().resolve(".project"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <projectDescription>
                      <name>%s</name>
                      <comment></comment>
                      <projects></projects>
                      <buildSpec>
                        <buildCommand>
                          <name>org.eclipse.jdt.core.javabuilder</name>
                          <arguments></arguments>
                        </buildCommand>
                      </buildSpec>
                      <natures>
                        <nature>org.eclipse.jdt.core.javanature</nature>
                      </natures>
                    </projectDescription>
                    """.formatted(escape(module.name())));
            Files.writeString(module.content().resolve(".classpath"), classpath(module));
        }
    }

    private static String classpath(Module module) {
        StringBuilder content = new StringBuilder();
        content.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        content.append("<classpath>\n");
        for (Path source : module.mainSources()) {
            content.append("  <classpathentry kind=\"src\" path=\"")
                    .append(escape(module.content().relativize(source).toString().replace(File.separatorChar, '/')))
                    .append("\"/>\n");
        }
        for (Path source : module.testSources()) {
            content.append("  <classpathentry kind=\"src\" output=\"bin/test\" path=\"")
                    .append(escape(module.content().relativize(source).toString().replace(File.separatorChar, '/')))
                    .append("\">\n");
            content.append("    <attributes>\n");
            content.append("      <attribute name=\"test\" value=\"true\"/>\n");
            content.append("    </attributes>\n");
            content.append("  </classpathentry>\n");
        }
        content.append("  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\"/>\n");
        for (String dependency : module.moduleDependencies()) {
            content.append("  <classpathentry combineaccessrules=\"false\" kind=\"src\" path=\"/")
                    .append(escape(dependency))
                    .append("\"/>\n");
        }
        for (Path library : module.libraries()) {
            content.append("  <classpathentry kind=\"lib\" path=\"")
                    .append(escape(library.toString().replace(File.separatorChar, '/')))
                    .append("\"/>\n");
        }
        content.append("  <classpathentry kind=\"output\" path=\"bin\"/>\n");
        content.append("</classpath>\n");
        return content.toString();
    }

    private void vscode(List<Module> modules, Path base) throws IOException {
        Path folder = Files.createDirectories(base.resolve(".vscode"));
        SequencedSet<String> sourcePaths = new LinkedHashSet<>();
        SequencedSet<String> libraries = new LinkedHashSet<>();
        for (Module module : modules) {
            for (Path source : module.mainSources()) {
                sourcePaths.add(workspace(base, source));
            }
            for (Path source : module.testSources()) {
                sourcePaths.add(workspace(base, source));
            }
            for (Path library : module.libraries()) {
                libraries.add(workspace(base, library));
            }
        }
        StringBuilder content = new StringBuilder();
        content.append("{\n");
        content.append("  \"java.project.sourcePaths\": [\n");
        content.append(array(sourcePaths));
        content.append("  ],\n");
        content.append("  \"java.project.referencedLibraries\": [\n");
        content.append(array(libraries));
        content.append("  ]\n");
        content.append("}\n");
        Files.writeString(folder.resolve("settings.json"), content.toString());
    }

    private static String array(Collection<String> values) {
        StringBuilder content = new StringBuilder();
        int index = 0;
        for (String value : values) {
            content.append("    \"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            content.append(++index < values.size() ? ",\n" : "\n");
        }
        return content.toString();
    }

    private static String workspace(Path base, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        return (normalized.startsWith(base)
                ? base.relativize(normalized).toString()
                : normalized.toString()).replace(File.separatorChar, '/');
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record Module(String name,
                          Path content,
                          List<Path> mainSources,
                          List<Path> testSources,
                          List<Path> libraries,
                          List<String> moduleDependencies) {
    }

    private record Raw(String name, Path content, boolean test, List<String> coordinates, List<Path> jars) {
    }
}
