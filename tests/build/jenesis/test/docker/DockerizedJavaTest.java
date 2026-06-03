package build.jenesis.test.docker;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.docker.DockerizedJava;

import static org.assertj.core.api.Assertions.assertThat;

public class DockerizedJavaTest {

    @TempDir
    private Path tmp;

    @Test
    public void mount_returns_new_instance() {
        DockerizedJava original = new DockerizedJava(tmp, "dummy");
        DockerizedJava mounted = original.mount(tmp, tmp.toString(), false);
        assertThat(mounted).isNotSameAs(original);
    }

    @Test
    public void parse_mounts_null_is_empty() {
        assertThat(DockerizedJava.Mount.parse(null, tmp)).isEmpty();
    }

    @Test
    public void parse_mounts_bare_uses_same_path_resolved_against_base() {
        assertThat(DockerizedJava.Mount.parse("lib", tmp)).singleElement().satisfies(mount -> {
            assertThat(mount.host()).isEqualTo(tmp.resolve("lib"));
            assertThat(mount.container()).isEqualTo(tmp.resolve("lib").toString());
        });
    }

    @Test
    public void parse_mounts_remaps_host_to_container() {
        assertThat(DockerizedJava.Mount.parse("lib:/opt/lib", tmp)).singleElement().satisfies(mount -> {
            assertThat(mount.host()).isEqualTo(tmp.resolve("lib"));
            assertThat(mount.container()).isEqualTo("/opt/lib");
        });
    }

    @Test
    public void parse_mounts_splits_comma_separated_entries() {
        assertThat(DockerizedJava.Mount.parse("a, b:/c", tmp)).hasSize(2);
    }

    @Test
    public void parse_mounts_keeps_windows_drive_colon_as_path() {
        assertThat(DockerizedJava.Mount.parse("C:/data", tmp)).singleElement().satisfies(mount ->
                assertThat(mount.container()).isEqualTo(mount.host().toString()));
    }

    @Test
    public void parse_env_null_is_empty() {
        assertThat(DockerizedJava.Env.parse(null)).isEmpty();
    }

    @Test
    public void parse_env_bare_name_forwards_host_value() {
        assertThat(DockerizedJava.Env.parse("FOO")).singleElement().satisfies(env -> {
            assertThat(env.name()).isEqualTo("FOO");
            assertThat(env.value()).isNull();
        });
    }

    @Test
    public void parse_env_name_value_sets_explicit_value() {
        assertThat(DockerizedJava.Env.parse("FOO=bar, BAZ=qux")).satisfiesExactly(
                first -> {
                    assertThat(first.name()).isEqualTo("FOO");
                    assertThat(first.value()).isEqualTo("bar");
                },
                second -> {
                    assertThat(second.name()).isEqualTo("BAZ");
                    assertThat(second.value()).isEqualTo("qux");
                });
    }

    @Test
    @EnabledIf("dockerAvailable")
    public void execute_returns_zero_on_success() throws IOException, InterruptedException {
        Files.writeString(tmp.resolve("Success.java"), """
                public class Success {
                    public static void main(String[] args) {
                        System.out.println("ok");
                    }
                }
                """);
        int code = new DockerizedJava(tmp).execute("Success.java", Map.of());
        assertThat(code).isEqualTo(0);
    }

    @Test
    @EnabledIf("dockerAvailable")
    public void execute_propagates_nonzero_exit_code() throws IOException, InterruptedException {
        Files.writeString(tmp.resolve("Failing.java"), """
                public class Failing {
                    public static void main(String[] args) {
                        System.exit(7);
                    }
                }
                """);
        int code = new DockerizedJava(tmp).execute("Failing.java", Map.of());
        assertThat(code).isEqualTo(7);
    }

    @Test
    @EnabledIf("dockerAvailable")
    public void execute_forwards_properties_and_args() throws IOException, InterruptedException {
        Files.writeString(tmp.resolve("Echo.java"), """
                public class Echo {
                    public static void main(String[] args) {
                        if (!"value".equals(System.getProperty("jenesis.test.flag"))) {
                            System.exit(11);
                        }
                        if (args.length != 1 || !"hello".equals(args[0])) {
                            System.exit(12);
                        }
                    }
                }
                """);
        int code = new DockerizedJava(tmp).execute(
                "Echo.java",
                Map.of("jenesis.test.flag", "value"),
                "hello");
        assertThat(code).isEqualTo(0);
    }

    static boolean dockerAvailable() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException _) {
            return false;
        }
    }
}
