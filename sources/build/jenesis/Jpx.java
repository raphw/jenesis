package build.jenesis;

import module java.base;
import build.jenesis.docker.DockerizedJava;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenDefaultVersionNegotiator;
import build.jenesis.maven.MavenModuleResolver;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenRepository;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.module.ModularJarResolver;

public record Jpx(Path storage,
                  Map<String, Repository> repositories,
                  Map<String, Resolver> resolvers,
                  HashDigestFunction hashFunction) {

    public static final String PROPERTIES = "jpx.properties";

    private static final int MINIMUM_CHECKSUM_LENGTH = 32;

    public Jpx() {
        this(Path.of(System.getProperty("user.home")).resolve(".jenesis").resolve("jpx"));
    }

    public Jpx(Path storage) {
        Repository local = JenesisModuleRepository.ofLocal();
        Map<String, Repository> repositories = new LinkedHashMap<>();
        repositories.put("maven", MavenDefaultRepository.of());
        repositories.put("module", local);
        repositories.put("modular", local);
        Map<String, Resolver> resolvers = new LinkedHashMap<>();
        MavenPomResolver maven = new MavenPomResolver();
        resolvers.put("maven", maven);
        resolvers.put("module", new MavenModuleResolver("maven", maven, local));
        resolvers.put("modular", new ModularJarResolver(false));
        this(storage,
                Collections.unmodifiableMap(repositories),
                Collections.unmodifiableMap(resolvers),
                new HashDigestFunction("SHA-256"));
    }

    public record Command(String name, String version, String mainClass) {

        public static Command parse(String argument) {
            int slash = argument.indexOf('/');
            String mainClass = slash < 0 ? null : argument.substring(slash + 1);
            if (mainClass != null) {
                requireClassName(mainClass);
            }
            String head = slash < 0 ? argument : argument.substring(0, slash);
            int at = head.lastIndexOf('@');
            String version = at < 0 ? null : head.substring(at + 1);
            if (version != null && version.isEmpty()) {
                throw new IllegalArgumentException("Empty version in: " + argument);
            }
            String name = at < 0 ? head : head.substring(0, at);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Empty name in: " + argument);
            }
            return new Command(name, version, mainClass);
        }

        String folder(String version) {
            return name.replace(":", "--") + "@" + version;
        }
    }

    public static final String HELP = """
            Usage: jpx [--modular] [--docker[=<image>]] [--hash=<checksum>] <target> [argument...]

            Runs the main entry point of a published module, resolving and installing
            it on first use.

            Target: <name>[@<version>][/<main-class>]

              The name is a module name, its coordinates discovered as a POM and the
              graph read from Maven metadata, or a <groupId>:<artifactId> pair, resolved
              directly. Without a version, the latest installed version is preferred,
              then the latest release is resolved. The main class defaults to the jar's
              module main class or Main-Class manifest entry - name one to override it,
              as in java -m <module>/<main-class>.

            Installations live in ~/.jenesis/jpx/<name>@<version>/ beside a
            jpx.properties descriptor listing paths, entry point and checksum.

            Options:
              --modular           resolve purely over module descriptors, walking requires
                                  clauses; every module must then be explicitly named
              --docker[=<image>]  run the program in a Docker container; resolution and
                                  installation still happen on the host, the installation
                                  and the host's Java home are mounted read-only. Without
                                  an image, a minimal hardened image is used
              --hash=<checksum>   verify the installed jars against a SHA-256 digest
                                  prefix (at least 32 hex characters) before launching
              --help              print this help""";

    public static void main(String... arguments) throws IOException, InterruptedException {
        boolean modular = false, dockerized = false;
        String image = null, checksum = null;
        int target = 0;
        while (target < arguments.length && arguments[target].startsWith("--")) {
            switch (arguments[target]) {
                case "--modular" -> modular = true;
                case "--docker" -> dockerized = true;
                case "--help" -> {
                    System.out.println(HELP);
                    System.exit(0);
                }
                default -> {
                    if (arguments[target].startsWith("--docker=")) {
                        dockerized = true;
                        image = arguments[target].substring("--docker=".length());
                    } else if (arguments[target].startsWith("--hash=")) {
                        checksum = arguments[target].substring("--hash=".length());
                    } else {
                        System.err.println("Unknown option: " + arguments[target]);
                        System.err.println(HELP);
                        System.exit(64);
                    }
                }
            }
            target++;
        }
        if (arguments.length == target) {
            System.err.println(HELP);
            System.exit(64);
        }
        Jpx jpx = new Jpx();
        Command command = Command.parse(arguments[target]);
        Path folder = jpx.install(command, modular, checksum);
        List<String> remaining = List.of(arguments).subList(target + 1, arguments.length);
        if (dockerized) {
            Path workingDirectory = Path.of("").toAbsolutePath();
            System.exit(jpx.launch(folder, command.mainClass(), remaining, image == null
                    ? new DockerizedJava(workingDirectory)
                    : new DockerizedJava(workingDirectory, image)));
        } else {
            System.exit(jpx.launch(folder, command.mainClass(), remaining));
        }
    }

    public Path install(Command command, boolean modular, String checksum) throws IOException {
        if (checksum != null) {
            checksum = requireValidChecksum(checksum);
        }
        if (command.version() == null) {
            Path installed = latestInstalled(command.name());
            if (installed != null) {
                verify(installed, checksum);
                return installed;
            }
        } else {
            Path folder = storage.resolve(command.folder(command.version()));
            if (Files.isRegularFile(folder.resolve(PROPERTIES))) {
                verify(folder, checksum);
                return folder;
            }
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Resolver.Resolution resolution;
            String version = command.version(), root;
            int colon = command.name().indexOf(':');
            if (colon < 0) {
                requireSafeSegment("module name", command.name());
                String prefix = modular ? "modular" : "module";
                SequencedMap<String, String> versions = new LinkedHashMap<>();
                if (version != null) {
                    versions.put(command.name(), version);
                }
                resolution = resolvers.get(prefix).dependencies(executor,
                        prefix,
                        repositories,
                        new LinkedHashMap<>(Map.of(command.name(), Collections.emptyNavigableSet())),
                        versions,
                        DependencyScope.RUNTIME);
                root = null;
                for (String coordinate : resolution.artifacts().sequencedKeySet()) {
                    if (coordinate.equals(prefix + "/" + command.name())
                            || coordinate.startsWith(prefix + "/" + command.name() + "/")) {
                        root = coordinate;
                        break;
                    }
                }
                if (root == null) {
                    throw new IllegalStateException("Resolution did not retain a root entry for " + command.name());
                }
                if (version == null) {
                    int slash = root.lastIndexOf('/');
                    version = slash < prefix.length() + 1 + command.name().length()
                            ? null
                            : root.substring(slash + 1);
                }
                if (version == null) {
                    throw new IllegalStateException("Cannot determine a version for " + command.name()
                            + " - specify one as " + command.name() + "@<version>");
                }
            } else {
                if (modular) {
                    throw new IllegalArgumentException("Pure module resolution requires a module name, "
                            + "not Maven coordinates: " + command.name());
                }
                String groupId = command.name().substring(0, colon), artifactId = command.name().substring(colon + 1);
                requireSafeSegment("group", groupId);
                requireSafeSegment("artifact", artifactId);
                if (version == null) {
                    version = MavenDefaultVersionNegotiator.maven().get().resolve(executor,
                            MavenRepository.of(repositories.get("maven")),
                            groupId, artifactId, "jar", null, "RELEASE");
                }
                resolution = resolvers.get("maven").dependencies(executor,
                        "maven",
                        repositories,
                        new LinkedHashMap<>(Map.of(groupId + "/" + artifactId, Collections.emptyNavigableSet())),
                        new LinkedHashMap<>(Map.of(groupId + "/" + artifactId, version)),
                        DependencyScope.RUNTIME);
                root = "maven/" + groupId + "/" + artifactId + "/" + version;
                if (!resolution.artifacts().containsKey(root)) {
                    throw new IllegalStateException("Resolution did not retain a root entry for " + command.name());
                }
            }
            requireSafeSegment("version", version);
            Path folder = storage.resolve(command.folder(version));
            if (!Files.isRegularFile(folder.resolve(PROPERTIES))) {
                Files.createDirectories(storage);
                try (FileChannel channel = FileChannel.open(storage.resolve(command.folder(version) + ".lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE); FileLock _ = channel.lock()) {
                    if (!Files.isRegularFile(folder.resolve(PROPERTIES))) {
                        install(command, version, resolution, resolution.artifacts().get(root).file(), folder);
                    }
                }
            }
            verify(folder, checksum);
            return folder;
        }
    }

    private void install(Command command,
                         String version,
                         Resolver.Resolution resolution,
                         Path root,
                         Path folder) throws IOException {
        if (Files.exists(folder)) {
            clear(folder);
        }
        Files.createDirectories(folder);
        SequencedMap<String, Path> jars = new TreeMap<>();
        for (Resolver.Resolved resolved : resolution.artifacts().values()) {
            Path file = resolved.file();
            if (jars.putIfAbsent(file.getFileName().toString(), file) == null) {
                BuildStep.linkOrCopy(folder.resolve(file.getFileName().toString()), file);
            }
        }
        ModuleDescriptor descriptor = PathPlacement.moduleDescriptor(root);
        if (command.name().indexOf(':') < 0
                && (descriptor == null || !descriptor.name().equals(command.name()))) {
            throw new IllegalStateException("The jar resolved for module " + command.name() + " is named "
                    + (descriptor == null ? "nothing" : descriptor.name()) + " - the repository mapping appears stale");
        }
        String mainModule = descriptor == null ? null : descriptor.name();
        String mainClass = descriptor == null || descriptor.mainClass().isEmpty()
                ? mainClassOf(root)
                : descriptor.mainClass().get();
        List<String> modulepath = new ArrayList<>(), classpath = new ArrayList<>();
        boolean selfContainedModuleGraph = true;
        for (Map.Entry<String, Path> entry : jars.entrySet()) {
            ModuleDescriptor placed = mainModule == null ? null : PathPlacement.moduleDescriptor(folder.resolve(entry.getKey()));
            (placed != null ? modulepath : classpath).add(entry.getKey());
            selfContainedModuleGraph &= placed != null && !placed.isAutomatic();
        }
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("name", command.name());
        properties.setProperty("version", version);
        if (mainClass != null) {
            properties.setProperty("mainClass", mainClass);
        }
        if (mainModule != null) {
            properties.setProperty("mainModule", mainModule);
        }
        if (!modulepath.isEmpty()) {
            properties.setProperty("modulepath", String.join(",", modulepath));
            properties.setProperty("selfContainedModuleGraph", Boolean.toString(selfContainedModuleGraph));
        }
        if (!classpath.isEmpty()) {
            properties.setProperty("classpath", String.join(",", classpath));
        }
        properties.setProperty("checksum", hashFunction.encoded(checksum(folder, jars.sequencedKeySet())));
        Path temporary = Files.createTempFile(folder, PROPERTIES, ".tmp");
        properties.store(temporary);
        Files.move(temporary, folder.resolve(PROPERTIES), StandardCopyOption.ATOMIC_MOVE);
    }

    public int launch(Path folder, String mainClass, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "java.exe" : "java").toString());
        command.addAll(javaArguments(folder, mainClass, arguments));
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    public int launch(Path folder, String mainClass, List<String> arguments, DockerizedJava docker)
            throws IOException, InterruptedException {
        return docker.mount(folder, folder.toString(), true).execute(javaArguments(folder, mainClass, arguments));
    }

    private static List<String> javaArguments(Path folder, String mainClass, List<String> arguments) throws IOException {
        SequencedProperties properties = SequencedProperties.ofFiles(folder.resolve(PROPERTIES));
        String main = mainClass == null ? properties.getProperty("mainClass") : mainClass;
        if (main == null) {
            throw new IllegalStateException("No main class: the installation " + folder.getFileName()
                    + " declares neither a module main class nor a Main-Class manifest attribute"
                    + " - name one as <name>[@<version>]/<main-class>");
        }
        List<String> command = new ArrayList<>();
        String modulepath = properties.getProperty("modulepath"), classpath = properties.getProperty("classpath");
        if (modulepath != null) {
            command.add("-p");
            command.add(join(folder, modulepath));
            if (!Boolean.parseBoolean(properties.getProperty("selfContainedModuleGraph"))) {
                command.add("--add-modules");
                command.add("ALL-MODULE-PATH");
            }
        }
        if (classpath != null) {
            command.add("-cp");
            command.add(join(folder, classpath));
        }
        String mainModule = properties.getProperty("mainModule");
        if (mainModule != null) {
            command.add("-m");
            command.add(mainModule + "/" + main);
        } else {
            command.add(main);
        }
        command.addAll(arguments);
        return command;
    }

    public Path latestInstalled(String name) throws IOException {
        if (!Files.isDirectory(storage)) {
            return null;
        }
        String prefix = new Command(name, null, null).folder("");
        Path latest = null;
        FileTime time = null;
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(storage)) {
            for (Path folder : folders) {
                if (!folder.getFileName().toString().startsWith(prefix) || !Files.isRegularFile(folder.resolve(PROPERTIES))) {
                    continue;
                }
                FileTime candidate = Files.getLastModifiedTime(folder.resolve(PROPERTIES));
                if (time == null || candidate.compareTo(time) > 0) {
                    latest = folder;
                    time = candidate;
                }
            }
        }
        return latest;
    }

    private void verify(Path folder, String checksum) throws IOException {
        if (checksum == null) {
            return;
        }
        SequencedProperties properties = SequencedProperties.ofFiles(folder.resolve(PROPERTIES));
        SequencedSet<String> names = new TreeSet<>();
        for (String path : new String[]{properties.getProperty("modulepath"), properties.getProperty("classpath")}) {
            if (path != null) {
                names.addAll(List.of(path.split(",")));
            }
        }
        String computed = HexFormat.of().formatHex(checksum(folder, names));
        if (!computed.startsWith(checksum)) {
            throw new IllegalStateException("Checksum mismatch for " + folder.getFileName()
                    + ": expected a digest starting with " + checksum + " but computed " + computed);
        }
    }

    private byte[] checksum(Path folder, SequencedCollection<String> names) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(hashFunction.algorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String name : new TreeSet<>(names)) {
            digest.update((name + "\t" + HexFormat.of().formatHex(hashFunction.hash(folder.resolve(name))) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return digest.digest();
    }

    private static String join(Path folder, String names) {
        return Arrays.stream(names.split(","))
                .map(name -> folder.resolve(name).toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String mainClassOf(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion())) {
            Manifest manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        }
    }

    private static void clear(Path folder) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                if (!directory.equals(folder)) {
                    Files.delete(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String requireValidChecksum(String checksum) {
        String normalized = checksum.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha-256/")) {
            normalized = normalized.substring("sha-256/".length());
        }
        if (normalized.length() < MINIMUM_CHECKSUM_LENGTH) {
            throw new IllegalArgumentException("A checksum requires at least " + MINIMUM_CHECKSUM_LENGTH
                    + " hex characters to remain secure, but got " + normalized.length() + ": " + normalized);
        }
        if (!normalized.chars().allMatch(character -> character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f')) {
            throw new IllegalArgumentException("Not a hexadecimal checksum: " + normalized);
        }
        return normalized;
    }

    private static void requireClassName(String value) {
        for (String segment : value.split("\\.", -1)) {
            if (segment.isEmpty() || !segment.chars().allMatch(Character::isJavaIdentifierPart)) {
                throw new IllegalArgumentException("Not a class name: " + value);
            }
        }
    }

    private static void requireSafeSegment(String role, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank " + role + " is not a valid coordinate");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean permitted = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '-'
                    || character == '_'
                    || character == '+';
            if (!permitted) {
                throw new IllegalArgumentException(
                        "Illegal " + role + " '" + value + "': character '" + character + "' is not permitted");
            }
        }
        for (String segment : value.split("\\.", -1)) {
            if (segment.equals("..") || segment.isEmpty()) {
                throw new IllegalArgumentException("Illegal " + role + " '" + value + "'");
            }
        }
    }
}
