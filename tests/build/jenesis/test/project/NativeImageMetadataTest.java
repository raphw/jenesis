package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.project.NativeImageMetadata;
import build.jenesis.step.Inventory;

import static org.assertj.core.api.Assertions.assertThat;

public class NativeImageMetadataTest {

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
    public void collects_only_the_test_module_that_names_this_modules_artifact() throws IOException {
        Path own = ownInventory("app.one");
        Path testOfOne = testInventory("test.one", "app.one", "one.Greeter");
        Path testOfTwo = testInventory("test.two", "app.two", "two.Greeter");

        run(argument("../inventory", own),
                argument("../selection/test.two/inventory", testOfTwo),
                argument("../selection/test.one/inventory", testOfOne));

        Path metadata = next.resolve("native-image").resolve("reachability-metadata.json");
        assertThat(metadata)
                .as("the image collects its own test module's capture, never a sibling's")
                .content()
                .contains("one.Greeter")
                .doesNotContain("two.Greeter");
    }

    @Test
    public void collects_nothing_when_no_test_module_names_this_modules_artifact() throws IOException {
        run(argument("../inventory", ownInventory("app.one")),
                argument("../selection/test.two/inventory", testInventory("test.two", "app.two", "two.Greeter")));

        assertThat(next.resolve("native-image"))
                .as("a module with no test capture for it produces no metadata")
                .doesNotExist();
    }

    @Test
    public void ignores_a_test_modules_capture_offered_outside_the_selection_namespace() throws IOException {
        run(argument("../inventory", ownInventory("app.one")),
                argument("../inventory-stray", testInventory("test.one", "app.one", "one.Greeter")));

        assertThat(next.resolve("native-image"))
                .as("a capture not wired under the selection namespace is not collected")
                .doesNotExist();
    }

    private Path ownInventory(String artifact) throws IOException {
        Path folder = Files.createDirectory(source.resolve("own-" + artifact));
        SequencedProperties inventory = new SequencedProperties();
        inventory.setProperty("module.artifact", artifact);
        inventory.store(folder.resolve(Inventory.INVENTORY));
        return folder;
    }

    private Path testInventory(String name, String tested, String greeter) throws IOException {
        Path folder = Files.createDirectory(source.resolve(name));
        SequencedProperties inventory = new SequencedProperties();
        inventory.setProperty("module-" + name + ".artifact", name);
        inventory.setProperty("module-" + name + ".test", tested);
        inventory.setProperty("module-" + name + ".nativeimage", "nativeimage");
        inventory.store(folder.resolve(Inventory.INVENTORY));
        Path captured = Files.createDirectory(folder.resolve("nativeimage"));
        Files.writeString(captured.resolve("reachability-metadata.json"),
                "{\"reflection\":[{\"type\":\"" + greeter + "\"}]}");
        return folder;
    }

    private Map.Entry<String, BuildStepArgument> argument(String key, Path folder) {
        return Map.entry(key, new BuildStepArgument(folder, Map.of()));
    }

    @SafeVarargs
    private void run(Map.Entry<String, BuildStepArgument>... arguments) throws IOException {
        SequencedMap<String, BuildStepArgument> map = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> argument : arguments) {
            map.put(argument.getKey(), argument.getValue());
        }
        BuildStepResult result = new NativeImageMetadata().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        map)
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
    }
}
