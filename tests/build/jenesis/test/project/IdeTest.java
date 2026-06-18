package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.project.Ide;
import build.jenesis.step.Inventory;

import static org.assertj.core.api.Assertions.assertThat;

public class IdeTest {

    @TempDir
    private Path root;
    private Path next, supplement;

    @BeforeEach
    public void setUp() throws IOException {
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
    }

    @Test
    public void idea_writes_iml_and_modules_xml() throws IOException {
        Files.createDirectories(root.resolve("greeter").resolve("sources"));
        Path inventory = inventory("module-greeter", properties -> {
            properties.setProperty("module-greeter.path", "greeter");
            properties.setProperty("module-greeter.dependency.0", "maven/org.example/lib/1.0 lib/lib.jar");
        });
        Path jar = Files.createDirectories(inventory.resolve("lib")).resolve("lib.jar");
        Files.writeString(jar, "library");

        run(Ide.IDEA, inventory);

        String iml = Files.readString(root.resolve("greeter").resolve("greeter.iml"));
        assertThat(iml).contains("<sourceFolder url=\"file://$MODULE_DIR$/sources\" isTestSource=\"false\"/>");
        assertThat(iml).contains("jar://$PROJECT_DIR$/"
                + slash(root.toAbsolutePath().normalize().relativize(jar.toAbsolutePath().normalize())) + "!/");
        String modules = Files.readString(root.resolve(".idea").resolve("modules.xml"));
        assertThat(modules).contains("$PROJECT_DIR$/greeter/greeter.iml");
        assertThat(root.resolve(".idea").resolve("misc.xml")).exists();
    }

    @Test
    public void idea_links_sibling_module_instead_of_jar() throws IOException {
        Files.createDirectories(root.resolve("greeter"));
        Files.createDirectories(root.resolve("app"));
        Path greeter = inventory("module-greeter", properties -> {
            properties.setProperty("module-greeter.path", "greeter");
            properties.setProperty("module-greeter.identity.0", "maven/org.example/greeter/1.0");
        });
        Path app = inventory("module-app", properties -> {
            properties.setProperty("module-app.path", "app");
            properties.setProperty("module-app.dependency.0", "maven/org.example/greeter/1.0 lib/greeter.jar");
        });

        run(Ide.IDEA, greeter, app);

        String iml = Files.readString(root.resolve("app").resolve("app.iml"));
        assertThat(iml).contains("<orderEntry type=\"module\" module-name=\"greeter\"/>");
        assertThat(iml).doesNotContain("greeter.jar");
    }

    @Test
    public void eclipse_writes_project_and_classpath() throws IOException {
        Files.createDirectories(root.resolve("greeter").resolve("sources"));
        Files.createDirectories(root.resolve("greeter").resolve("test"));
        Path inventory = inventory("module-greeter", properties -> {
            properties.setProperty("module-greeter.path", "greeter");
            properties.setProperty("module-greeter.dependency.0", "maven/org.example/lib/1.0 lib/lib.jar");
        });

        run(Ide.ECLIPSE, inventory);

        String project = Files.readString(root.resolve("greeter").resolve(".project"));
        assertThat(project).contains("<name>greeter</name>");
        assertThat(project).contains("org.eclipse.jdt.core.javanature");
        String classpath = Files.readString(root.resolve("greeter").resolve(".classpath"));
        assertThat(classpath).contains("<classpathentry kind=\"src\" path=\"sources\"/>");
        assertThat(classpath).contains("<attribute name=\"test\" value=\"true\"/>");
        assertThat(classpath).contains("kind=\"lib\" path=\"" + slash(inventory.resolve("lib/lib.jar").toAbsolutePath().normalize()));
        assertThat(classpath).contains("org.eclipse.jdt.launching.JRE_CONTAINER");
    }

    @Test
    public void vscode_writes_settings() throws IOException {
        Files.createDirectories(root.resolve("greeter").resolve("sources"));
        Path inventory = inventory("module-greeter", properties -> {
            properties.setProperty("module-greeter.path", "greeter");
            properties.setProperty("module-greeter.dependency.0", "maven/org.example/lib/1.0 lib/lib.jar");
        });

        run(Ide.VSCODE, inventory);

        String settings = Files.readString(root.resolve(".vscode").resolve("settings.json"));
        assertThat(settings).contains("\"java.project.sourcePaths\"");
        assertThat(settings).contains("\"greeter/sources\"");
        assertThat(settings).contains("\"java.project.referencedLibraries\"");
        assertThat(settings).contains("greeter/lib/lib.jar");
    }

    @Test
    public void falls_back_to_module_folder_when_no_source_dir() throws IOException {
        Files.createDirectories(root.resolve("greeter"));
        Path inventory = inventory("module-greeter", properties ->
                properties.setProperty("module-greeter.path", "greeter"));

        run(Ide.IDEA, inventory);

        String iml = Files.readString(root.resolve("greeter").resolve("greeter.iml"));
        assertThat(iml).contains("<sourceFolder url=\"file://$MODULE_DIR$\" isTestSource=\"false\"/>");
    }

    private static String slash(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private Path inventory(String prefix, Consumer<SequencedProperties> values) throws IOException {
        Path folder = Files.createDirectories(root.resolve("out").resolve(prefix));
        SequencedProperties inventory = new SequencedProperties();
        values.accept(inventory);
        inventory.store(folder.resolve(Inventory.INVENTORY));
        return folder;
    }

    private void run(String tool, Path... inventories) throws IOException {
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        int index = 0;
        for (Path inventory : inventories) {
            arguments.put("inventory-" + index++, new BuildStepArgument(inventory, Map.of()));
        }
        BuildStepResult result = new Ide(root, tool).apply(Runnable::run,
                        new BuildStepContext(root.resolve("previous"), next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
    }
}
