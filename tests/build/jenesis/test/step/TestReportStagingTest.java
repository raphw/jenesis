package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;
import build.jenesis.step.TestReportStaging;

import static org.assertj.core.api.Assertions.assertThat;

public class TestReportStagingTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, source;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        source = Files.createDirectory(root.resolve("source"));
    }

    @Test
    public void stages_reports_under_module_folder() throws IOException {
        Path folder = stagedReports("foo", "junit-platform-events-1.xml", "junit-platform-events-2.xml");

        BuildStepResult result = run(folder);

        assertThat(result.next()).isTrue();
        assertThat(next.resolve("module-foo/junit-platform-events-1.xml")).hasContent("<events/>");
        assertThat(next.resolve("module-foo/junit-platform-events-2.xml")).hasContent("<events/>");
    }

    @Test
    public void keeps_each_module_reports_separately() throws IOException {
        Path foo = stagedReports("foo", "junit-platform-events-1.xml");
        Path bar = stagedReports("bar", "junit-platform-events-1.xml");

        run(foo, bar);

        assertThat(next.resolve("module-foo/junit-platform-events-1.xml")).exists();
        assertThat(next.resolve("module-bar/junit-platform-events-1.xml")).exists();
    }

    @Test
    public void arguments_without_inventory_are_skipped() throws IOException {
        Path stray = Files.createDirectory(source.resolve("stray"));
        Files.writeString(Files.createDirectory(stray.resolve("testreport")).resolve("junit-platform-events-1.xml"), "x");

        BuildStepResult result = run(stray);

        assertThat(result.next()).isTrue();
        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    public void modules_without_reports_stage_nothing() throws IOException {
        Path folder = Files.createDirectory(source.resolve("foo"));
        SequencedProperties inventory = new SequencedProperties();
        inventory.setProperty("module-foo.artifacts.0", "artifacts/classes.jar");
        inventory.store(folder.resolve(Inventory.INVENTORY));

        run(folder);

        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private Path stagedReports(String folderName, String... files) throws IOException {
        Path folder = Files.createDirectory(source.resolve(folderName));
        Path reports = Files.createDirectory(folder.resolve("testreport"));
        SequencedProperties inventory = new SequencedProperties();
        int index = 0;
        for (String file : files) {
            Files.writeString(reports.resolve(file), "<events/>");
            inventory.setProperty("module-" + folderName + ".testreport." + index++, "testreport/" + file);
        }
        inventory.store(folder.resolve(Inventory.INVENTORY));
        return folder;
    }

    private BuildStepResult run(Path... inventoryFolders) throws IOException {
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        for (Path folder : inventoryFolders) {
            arguments.put(folder.getFileName().toString(), new BuildStepArgument(folder, Map.of()));
        }
        return new TestReportStaging().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
    }
}
