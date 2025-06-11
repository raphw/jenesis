package build.buildbuddy.step;

import module java.base;

public sealed interface ProcessHandler permits ProcessHandler.OfTool, ProcessHandler.OfProcess {

    List<String> commands();

    int execute(Path output, Path error) throws IOException;

    final class OfTool implements ProcessHandler {

        private final ToolProvider toolProvider;

        private final List<String> commands;

        private OfTool(ToolProvider toolProvider, List<String> commands) {
            this.toolProvider = toolProvider;
            this.commands = commands;
        }

        public static Function<List<String>, ProcessHandler> of(ToolProvider toolProvider) {
            return arguments -> new OfTool(toolProvider, arguments);
        }

        public static Function<List<String>, ProcessHandler> of(String name) {
            return of(ToolProvider.findFirst(name).orElseThrow(() -> new IllegalArgumentException("No tool: " + name)));
        }

        @Override
        public List<String> commands() {
            return Stream.concat(Stream.of(toolProvider.name()), commands.stream()).toList();
        }

        @Override
        public int execute(Path output, Path error) throws IOException {
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output));
                 PrintWriter err = new PrintWriter(Files.newBufferedWriter(error))) {
                return toolProvider.run(out, err, commands.toArray(String[]::new));
            }
        }
    }

    final class OfProcess implements ProcessHandler {

        private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

        private final List<String> commands;

        private OfProcess(List<String> commands) {
            this.commands = commands;
        }

        static Function<List<String>, OfProcess> ofJavaHome(String command) {
            String home = System.getProperty("java.home");
            if (home == null) {
                home = System.getenv("JAVA_HOME");
            }
            if (home == null) {
                throw new IllegalStateException("Neither JAVA_HOME environment or java.home property set");
            } else {
                File program = new File(home, command);
                if (program.isFile()) {
                    return of(List.of(program.getPath() + (WINDOWS ? ".exe" : "")));
                } else {
                    throw new IllegalStateException("Could not find command " + command + " in " + home);
                }
            }
        }

        public static Function<List<String>, OfProcess> of(List<String> program) {
            return arguments -> new OfProcess(Stream.concat(program.stream(), arguments.stream()).toList());
        }

        @Override
        public List<String> commands() {
            return commands;
        }

        @Override
        public int execute(Path output, Path error) throws IOException {
            Process process = new ProcessBuilder(commands)
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .start();
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
