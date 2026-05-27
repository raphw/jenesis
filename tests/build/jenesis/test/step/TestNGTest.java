package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.project.TestNG;

import static org.assertj.core.api.Assertions.assertThat;

public class TestNGTest {

    @Test
    public void emits_nothing_when_no_classes_or_methods_given() {
        List<String> commands = new TestNG().commands(List.of(), new LinkedHashMap<>());
        assertThat(commands).isEmpty();
    }

    @Test
    public void joins_class_arguments_into_single_testclass_argument() {
        List<String> commands = new TestNG().commands(List.of("a.A", "b.B"), new LinkedHashMap<>());
        assertThat(commands).containsExactly("-testclass", "a.A,b.B");
    }

    @Test
    public void joins_method_arguments_into_single_methods_argument() {
        SequencedMap<String, List<String>> methods = new LinkedHashMap<>();
        methods.put("a.A", List.of("m1", "m2"));
        methods.put("b.B", List.of("n"));
        List<String> commands = new TestNG().commands(List.of(), methods);
        assertThat(commands).containsExactly("-methods", "a.A.m1,a.A.m2,b.B.n");
    }

    @Test
    public void combines_class_and_method_arguments() {
        SequencedMap<String, List<String>> methods = new LinkedHashMap<>();
        methods.put("p.Q", List.of("only"));
        List<String> commands = new TestNG().commands(List.of("a.A"), methods);
        assertThat(commands).containsExactly("-testclass", "a.A", "-methods", "p.Q.only");
    }
}
