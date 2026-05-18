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
