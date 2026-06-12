package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;
import build.jenesis.step.Tree;

import static org.assertj.core.api.Assertions.assertThat;

public class TreeTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, argument;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        argument = Files.createDirectory(root.resolve("argument"));
    }

    @Test
    public void renders_the_resolution_graph_from_the_inventory() throws IOException {
        SequencedProperties graph = new SequencedProperties();
        graph.setProperty("edge/0", "main\tcompile\tmaven\ttrue\tcompile\t1.0\t\tmaven/org.foo/bar/1.0");
        graph.setProperty("edge/1", "main\tcompile\tmaven\ttrue\tcompile\t2.0\tmaven/org.foo/bar/1.0\tmaven/org.foo/baz/2.0");
        graph.setProperty("vertex/main/compile/maven/org.foo/bar", "1.0\torg.foo.bar\tfalse");
        graph.setProperty("vertex/main/compile/maven/org.foo/baz", "2.0\t\tfalse");
        graph.store(argument.resolve("graph.properties"));
        SequencedProperties licenses = new SequencedProperties();
        licenses.setProperty("maven/org.foo/bar/1.0#0#name", "Apache-2.0");
        licenses.store(argument.resolve("licenses.properties"));
        SequencedProperties inventory = new SequencedProperties();
        inventory.setProperty("module.graph.0", "graph.properties");
        inventory.setProperty("module.licenses.0", "licenses.properties");
        inventory.store(argument.resolve(Inventory.INVENTORY));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BuildStepResult result = new Tree(new PrintStream(bytes, true, StandardCharsets.UTF_8)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                        argument,
                        Map.of(Path.of(Inventory.INVENTORY), Checksum.ADDED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        String text = bytes.toString(StandardCharsets.UTF_8).replaceAll("\033\\[[0-9;]*m", "");
        assertThat(text).contains("main/compile (module)");
        assertThat(text).contains("maven/org.foo/bar 1.0 [compile] (module org.foo.bar) {Apache-2.0}");
        assertThat(text).contains("└─ maven/org.foo/baz 2.0 [compile]");
        assertThat(text).contains("maven/org.foo/bar -> 1.0");
    }
}
