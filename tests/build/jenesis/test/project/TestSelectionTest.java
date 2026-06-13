package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.project.TestSelection;

import static org.assertj.core.api.Assertions.assertThat;

public class TestSelectionTest {

    @Test
    public void selects_direct_and_transitive_dependents() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("a.Leaf", reference("a.Leaf"));
        classes.put("a.Middle", reference("a.Middle", "a.Leaf"));
        classes.put("a.Top", reference("a.Top", "a.Middle"));
        classes.put("a.Unrelated", reference("a.Unrelated"));

        assertThat(TestSelection.of(classes).impacted(List.of("a.Leaf")))
                .containsExactlyInAnyOrder("a.Leaf", "a.Middle", "a.Top");
    }

    @Test
    public void selects_subclasses_through_the_superclass_reference() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("a.Base", reference("a.Base"));
        classes.put("a.Sub", extending("a.Sub", "a.Base"));

        assertThat(TestSelection.of(classes).impacted(List.of("a.Base")))
                .containsExactlyInAnyOrder("a.Base", "a.Sub");
    }

    @Test
    public void leaves_a_change_without_dependents_to_itself() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("a.Independent", reference("a.Independent"));
        classes.put("a.User", reference("a.User", "a.Independent"));

        assertThat(TestSelection.of(classes).impacted(List.of("a.User")))
                .containsExactly("a.User");
    }

    @Test
    public void ignores_references_outside_the_project() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("a.Only", reference("a.Only", "java.lang.String"));

        assertThat(TestSelection.of(classes).impacted(List.of("java.lang.String"))).isEmpty();
        assertThat(TestSelection.of(classes).impacted(List.of("a.Only"))).containsExactly("a.Only");
    }

    private static byte[] reference(String name, String... fieldTypes) {
        return ClassFile.of().build(ClassDesc.of(name), builder -> {
            int index = 0;
            for (String fieldType : fieldTypes) {
                builder.withField("field" + index++, ClassDesc.of(fieldType), ClassFile.ACC_PRIVATE);
            }
        });
    }

    private static byte[] extending(String name, String superName) {
        return ClassFile.of().build(ClassDesc.of(name), builder -> builder.withSuperclass(ClassDesc.of(superName)));
    }
}
