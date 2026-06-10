package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;
import build.jenesis.step.ReportStaging;

import static org.assertj.core.api.Assertions.assertThat;

public class ReportStagingTest {

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
    public void stages_reports_under_kind_and_module_folder() throws IOException {
        Path folder = module("foo", Map.of("tests", List.of("events-1.xml", "events-2.xml")));

        BuildStepResult result = run(folder);

        assertThat(result.next()).isTrue();
        assertThat(next.resolve("tests/foo/events-1.xml")).hasContent("<events/>");
        assertThat(next.resolve("tests/foo/events-2.xml")).hasContent("<events/>");
    }

    @Test
    public void stages_each_kind_in_its_own_folder() throws IOException {
        Path folder = module("foo", Map.of(
                "tests", List.of("events-1.xml"),
                "checkstyle", List.of("checkstyle-report.xml")));

        run(folder);

        assertThat(next.resolve("tests/foo/events-1.xml")).exists();
        assertThat(next.resolve("checkstyle/foo/checkstyle-report.xml")).exists();
    }

    @Test
    public void keeps_each_module_separately() throws IOException {
        Path foo = module("foo", Map.of("tests", List.of("events-1.xml")));
        Path bar = module("bar", Map.of("tests", List.of("events-1.xml")));

        run(foo, bar);

        assertThat(next.resolve("tests/foo/events-1.xml")).exists();
        assertThat(next.resolve("tests/bar/events-1.xml")).exists();
    }

    @Test
    public void arguments_without_inventory_are_skipped() throws IOException {
        Path stray = Files.createDirectory(source.resolve("stray"));
        Files.writeString(Files.createDirectories(stray.resolve("reports").resolve("tests"))
                .resolve("events-1.xml"), "x");

        BuildStepResult result = run(stray);

        assertThat(result.next()).isTrue();
        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    public void modules_without_report_keys_stage_nothing() throws IOException {
        Path folder = Files.createDirectory(source.resolve("foo"));
        SequencedProperties inventory = new SequencedProperties();
        inventory.setProperty("module-foo.artifacts.0", "artifacts/classes.jar");
        inventory.store(folder.resolve(Inventory.INVENTORY));

        run(folder);

        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private Path module(String name, Map<String, List<String>> reportsByKind) throws IOException {
        Path folder = Files.createDirectory(source.resolve(name));
        SequencedProperties inventory = new SequencedProperties();
        for (Map.Entry<String, List<String>> entry : reportsByKind.entrySet()) {
            Path reports = Files.createDirectories(folder.resolve("reports").resolve(entry.getKey()));
            for (String file : entry.getValue()) {
                Files.writeString(reports.resolve(file), "<events/>");
            }
            inventory.setProperty("module-" + name + ".report." + entry.getKey(), "reports/" + entry.getKey());
        }
        inventory.store(folder.resolve(Inventory.INVENTORY));
        return folder;
    }

    private BuildStepResult run(Path... inventoryFolders) throws IOException {
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        for (Path folder : inventoryFolders) {
            arguments.put(folder.getFileName().toString(), new BuildStepArgument(folder, Map.of()));
        }
        return new ReportStaging().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
    }
}
