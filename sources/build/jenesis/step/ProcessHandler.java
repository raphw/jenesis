package build.jenesis.step;

import module java.base;

public sealed interface ProcessHandler permits ProcessHandler.OfTool, ProcessHandler.OfProcess {

    List<String> commands();

    int execute(Path output, Path error, Tee tee) throws IOException;

    record Tee(Executor executor, Consumer<String> out, Consumer<String> err) {
    }

    private static Charset encoding() {
        String name = System.getProperty("native.encoding");
        if (name == null) {
            return Charset.defaultCharset();
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException _) {
            return Charset.defaultCharset();
        }
    }

    enum Factory {
        TOOL {
            @Override
            Function<List<String>, ? extends ProcessHandler> apply(String tool, String fork) {
                return ProcessHandler.OfTool.of(tool);
            }
        },
        FORK {
            @Override
            Function<List<String>, ? extends ProcessHandler> apply(String tool, String fork) {
                return ProcessHandler.OfProcess.ofJavaHome(fork);
            }
        };

        public static Factory of() {
            String factory = System.getProperty("jenesis.process.factory");
            if (factory == null) {
                if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
                    return TOOL;
                }
                return ToolProvider.findFirst("javac").isPresent() ? TOOL : FORK;
            }
            return switch (factory) {
                case "tool" -> TOOL;
                case "fork" -> FORK;
                default -> throw new IllegalArgumentException(
                        "Unknown process factory: " + factory + " (expected 'tool' or 'fork')");
            };
        }

        abstract Function<List<String>, ? extends ProcessHandler> apply(String tool, String fork);
    }

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
        public int execute(Path output, Path error, Tee tee) throws IOException {
            if (tee == null) {
                try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output, encoding()));
                     PrintWriter err = new PrintWriter(Files.newBufferedWriter(error, encoding()))) {
                    return toolProvider.run(out, err, commands.toArray(String[]::new));
                }
            }
            try (PrintWriter out = new PrintWriter(new LineTee(Files.newBufferedWriter(output, encoding()), tee.out()), true);
                 PrintWriter err = new PrintWriter(new LineTee(Files.newBufferedWriter(error, encoding()), tee.err()), true)) {
                return toolProvider.run(out, err, commands.toArray(String[]::new));
            }
        }

        private static final class LineTee extends Writer {

            private final Writer delegate;
            private final Consumer<String> consumer;
            private final StringBuilder line = new StringBuilder();

            private LineTee(Writer delegate, Consumer<String> consumer) {
                this.delegate = delegate;
                this.consumer = consumer;
            }

            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                delegate.write(buffer, offset, length);
                for (int index = 0; index < length; index++) {
                    char character = buffer[offset + index];
                    if (character == '\n') {
                        consumer.accept(line.toString());
                        line.setLength(0);
                    } else if (character != '\r') {
                        line.append(character);
                    }
                }
            }

            @Override
            public void flush() throws IOException {
                delegate.flush();
            }

            @Override
            public void close() throws IOException {
                try {
                    if (!line.isEmpty()) {
                        consumer.accept(line.toString());
                        line.setLength(0);
                    }
                } finally {
                    delegate.close();
                }
            }
        }
    }

    final class OfProcess implements ProcessHandler {

        private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        private final List<String> commands;

        private OfProcess(List<String> commands) {
            this.commands = commands;
        }

        public static Function<List<String>, OfProcess> ofJavaHome(String command) {
            String home = System.getProperty("java.home");
            if (home == null) {
                home = System.getenv("JAVA_HOME");
            }
            if (home == null) {
                throw new IllegalStateException("Neither JAVA_HOME environment or java.home property set");
            } else {
                File program = new File(home, command + (WINDOWS ? ".exe" : ""));
                if (program.isFile()) {
                    return of(List.of(program.getPath()));
                } else {
                    throw new IllegalStateException("Could not find command " + program.getPath() + " in " + home);
                }
            }
        }

        public static Function<List<String>, OfProcess> ofCommand(String command) {
            return arguments -> new OfProcess(Stream.concat(
                    Stream.of(locate(command)),
                    arguments.stream()).toList());
        }

        private static String locate(String command) {
            String name = command + (WINDOWS ? ".exe" : "");
            List<String> homes = new ArrayList<>();
            String graalvm = System.getenv("GRAALVM_HOME");
            if (graalvm != null) {
                homes.add(graalvm);
            }
            String java = System.getProperty("java.home");
            if (java != null) {
                homes.add(java);
            }
            for (String home : homes) {
                File program = new File(new File(home, "bin"), name);
                if (program.isFile()) {
                    return program.getPath();
                }
            }
            String path = System.getenv("PATH");
            if (path != null) {
                for (String entry : path.split(File.pathSeparator)) {
                    File program = new File(entry, name);
                    if (program.isFile() && program.canExecute()) {
                        return program.getPath();
                    }
                }
            }
            throw new IllegalStateException("Could not locate '"
                    + command
                    + "' in GRAALVM_HOME, java.home/bin, or PATH");
        }

        public static Function<List<String>, OfProcess> of(List<String> program) {
            return arguments -> new OfProcess(Stream.concat(program.stream(), arguments.stream()).toList());
        }

        @Override
        public List<String> commands() {
            return commands;
        }

        @Override
        public int execute(Path output, Path error, Tee tee) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(commands);
            if (tee == null) {
                builder.redirectOutput(output.toFile()).redirectError(error.toFile());
            }
            builder.environment().putIfAbsent("COLUMNS", "80");
            builder.environment().putIfAbsent("LINES", "24");
            builder.environment().putIfAbsent("TERM", "dumb");
            Process process = builder.start();
            process.getOutputStream().close();
            CompletableFuture<Void> errored = null;
            if (tee != null) {
                CompletableFuture<Void> target = new CompletableFuture<>();
                errored = target;
                tee.executor().execute(() -> {
                    try {
                        drain(process.getErrorStream(), error, tee.err());
                        target.complete(null);
                    } catch (Throwable t) {
                        target.completeExceptionally(t);
                    }
                });
            }
            try {
                if (tee != null) {
                    drain(process.getInputStream(), output, tee.out());
                }
                int code = process.waitFor();
                if (errored != null) {
                    errored.join();
                }
                return code;
            } catch (InterruptedException e) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        private static void drain(InputStream stream, Path file, Consumer<String> consumer) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, encoding()));
                 BufferedWriter writer = Files.newBufferedWriter(file, encoding())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    consumer.accept(line);
                }
            }
        }
    }
}
