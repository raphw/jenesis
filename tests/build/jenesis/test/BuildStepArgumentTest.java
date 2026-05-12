package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.ChecksumStatus;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildStepArgumentTest {

    @Test
    public void hasChanged_no_args_returns_true_when_any_file_altered() {
        BuildStepArgument argument = new BuildStepArgument(Path.of("/tmp"),
                Map.of(Path.of("a.txt"), ChecksumStatus.ALTERED,
                        Path.of("b.txt"), ChecksumStatus.RETAINED));
        assertThat(argument.hasChanged()).isTrue();
    }

    @Test
    public void hasChanged_no_args_returns_false_when_all_retained() {
        BuildStepArgument argument = new BuildStepArgument(Path.of("/tmp"),
                Map.of(Path.of("a.txt"), ChecksumStatus.RETAINED));
        assertThat(argument.hasChanged()).isFalse();
    }

    @Test
    public void hasChanged_with_specific_prefix_matches_files_under_that_prefix() {
        BuildStepArgument argument = new BuildStepArgument(Path.of("/tmp"),
                Map.of(Path.of("compile/requires.properties"), ChecksumStatus.ALTERED,
                        Path.of("runtime/requires.properties"), ChecksumStatus.RETAINED));
        assertThat(argument.hasChanged(Path.of("compile"))).isTrue();
        assertThat(argument.hasChanged(Path.of("runtime"))).isFalse();
    }

    @Test
    public void hasChanged_with_dot_prefix_matches_any_file() {
        BuildStepArgument argument = new BuildStepArgument(Path.of("/tmp"),
                Map.of(Path.of("module-info.java"), ChecksumStatus.ALTERED));
        assertThat(argument.hasChanged(Path.of("."))).isTrue();
    }

    @Test
    public void hasChanged_with_dot_prefix_is_false_when_all_retained() {
        BuildStepArgument argument = new BuildStepArgument(Path.of("/tmp"),
                Map.of(Path.of("module-info.java"), ChecksumStatus.RETAINED,
                        Path.of("Foo.java"), ChecksumStatus.RETAINED));
        assertThat(argument.hasChanged(Path.of("."))).isFalse();
    }

    @Test
    public void hasChanged_with_dot_among_multiple_prefixes_still_matches_anything() {
        BuildStepArgument argument = new BuildStepArgument(Path.of("/tmp"),
                Map.of(Path.of("nested/Bar.java"), ChecksumStatus.ALTERED));
        assertThat(argument.hasChanged(Path.of("compile"), Path.of("."))).isTrue();
    }

    @Test
    public void hasChanged_with_specific_prefix_ignores_files_outside() {
        BuildStepArgument argument = new BuildStepArgument(Path.of("/tmp"),
                Map.of(Path.of("module-info.java"), ChecksumStatus.ALTERED));
        assertThat(argument.hasChanged(Path.of("compile"))).isFalse();
    }
}
