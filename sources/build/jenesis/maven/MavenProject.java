package build.jenesis.maven;

import module java.base;
import module java.xml;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.DependenciesModule;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.Assign;
import build.jenesis.step.Bind;
import build.jenesis.step.Javac;

import static build.jenesis.BuildStep.IDENTITY;

public class MavenProject implements BuildExecutorModule {

    public static final String POM = "pom/", MAVEN = "maven/";

    private static final String MODULE = "module", DEPENDENCIES = "dependencies", PREPARE = "prepare";

    private final Path root;
    private final String prefix;
    private final MavenRepository repository;
    private final MavenPomResolver resolver;

    public MavenProject(Path root, String prefix, MavenRepository repository, MavenPomResolver resolver) {
        this.root = root;
        this.prefix = prefix;
        this.repository = repository;
        this.resolver = resolver;
    }

    public static BuildExecutorModule make(Path root,
                                           String algorithm,
                                           Function<MavenModuleDescriptor, BuildExecutorModule> builder) {
        return make(root, "maven", algorithm, new MavenDefaultRepository(), new MavenPomResolver(), builder);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           String algorithm,
                                           MavenRepository mavenRepository,
                                           MavenPomResolver mavenResolver,
                                           Function<MavenModuleDescriptor, BuildExecutorModule> builder) {
        return new MultiProjectModule(new MavenProject(root, prefix, mavenRepository, mavenResolver),
                identifier -> identifier.startsWith(MODULE + "/")
                        ? Optional.of(identifier.substring(MODULE.length() + 1, identifier.indexOf('/', MODULE.length() + 1)))
                        : Optional.empty(),
                _ -> (name, dependencies, _) -> (buildExecutor, inherited) -> {
                    Map<String, Repository> mergedRepositories = Repository.prepend(Map.of(prefix, mavenRepository),
                            Repository.ofProperties(BuildStep.IDENTITY,
                                    inherited.entrySet().stream()
                                            .filter(entry ->
                                                    entry.getKey().startsWith(PREVIOUS + "module-")
                                                            && entry.getKey().endsWith("/assign"))
                                            .map(Map.Entry::getValue)
                                            .toList(),
                                    (folder, file) -> folder.resolve(file).normalize().toUri(),
                                    null));
                    Map<String, Resolver> resolverMap = Map.of(prefix, mavenResolver);
                    for (Map.Entry<String, Boolean> entry : List.of(
                            Map.entry(MultiProjectModule.COMPILE, true),
                            Map.entry(MultiProjectModule.RUNTIME, false))) {
                        buildExecutor.addModule(entry.getKey(), (scopeExec, scopeInherited) -> {
                            scopeExec.addStep(PREPARE,
                                    new MultiProjectDependencies(
                                            algorithm,
                                            identifier -> identifier.contains("/" + MultiProjectModule.IDENTIFIER + "/" + MODULE + "/" + name + "/"),
                                            entry.getKey()),
                                    scopeInherited.sequencedKeySet());
                            scopeExec.addModule(DEPENDENCIES,
                                    new DependenciesModule(mergedRepositories, resolverMap, entry.getValue()).computeChecksums(algorithm),
                                    PREPARE);
                        }, inherited.sequencedKeySet());
                    }
                    buildExecutor.addModule("produce",
                            builder.apply(new MavenModuleDescriptor(name, dependencies.sequencedKeySet())),
                            Stream.concat(
                                            inherited.sequencedKeySet().stream(),
                                            Stream.of(
                                                    MultiProjectModule.COMPILE + "/" + DEPENDENCIES + "/" + MultiProjectModule.CHECKED,
                                                    MultiProjectModule.COMPILE + "/" + DEPENDENCIES + "/" + MultiProjectModule.ARTIFACTS,
                                                    MultiProjectModule.RUNTIME + "/" + DEPENDENCIES + "/" + MultiProjectModule.CHECKED,
                                                    MultiProjectModule.RUNTIME + "/" + DEPENDENCIES + "/" + MultiProjectModule.ARTIFACTS))
                                    .collect(Collectors.<String, String, String, LinkedHashMap<String, String>>toMap(
                                            Function.identity(),
                                            key -> switch (key) {
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + MODULE + "/"
                                                        + name + "/"
                                                        + MultiProjectModule.SOURCES) -> MultiProjectModule.SOURCES;
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + MODULE + "/"
                                                        + name + "/"
                                                        + MultiProjectModule.MANIFESTS) -> MultiProjectModule.MANIFESTS;
                                                default -> key;
                                            },
                                            (a, _) -> a,
                                            LinkedHashMap::new)));
                    buildExecutor.addStep("assign",
                            new Assign((BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> & Serializable) ((coordinates, files) -> {
                                Path resolved = files.stream()
                                        .filter(file -> file.getFileName() != null
                                                && "classes.jar".equals(file.getFileName().toString()))
                                        .findFirst()
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                "Expected a classes.jar artifact: " + files));
                                return coordinates.stream().collect(Collectors.toMap(
                                        Function.identity(),
                                        _ -> resolved));
                            })),
                            Stream.concat(
                                    inherited.sequencedKeySet().stream().filter(identifier -> identifier.startsWith(
                                            MultiProjectModule.IDENTIFIER_PATH)),
                                    Stream.of("produce")));
                });
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        if (!Files.exists(root.resolve("pom.xml"))) {
            return;
        }
        buildExecutor.addStep("scan", new Scan(root));
        buildExecutor.addStep("prepare", new Prepare(prefix, resolver, repository), "scan");
        buildExecutor.addModule(MODULE, (modules, paths) -> {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(
                    paths.get("../prepare").resolve(MAVEN),
                    "*.properties")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    modules.addModule(name.substring(0, name.length() - 11), (module, _) -> {
                        Properties properties = new SequencedProperties();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            properties.load(reader);
                        }
                        boolean active = false;
                        Path base = root.resolve(properties.getProperty("path"));
                        if (!properties.getProperty("sources").isEmpty()) {
                            Path sources = base.resolve(properties.getProperty("sources"));
                            if (Files.exists(sources)) {
                                module.addSource("sources", Bind.asSources(), sources);
                                active = true;
                            }
                        }
                        int index = 0;
                        if (!properties.getProperty("resources").isEmpty()) {
                            for (String resource : properties.getProperty("resources").split(",")) {
                                Path resources = base.resolve(resource);
                                if (Files.exists(resources)) {
                                    module.addSource("resources-" + ++index, Bind.asResources(), resources);
                                    active = true;
                                }
                            }
                        }
                        if (active) {
                            module.addStep(MultiProjectModule.MANIFESTS, (_, context, _) -> {
                                Properties coordinates = new SequencedProperties();
                                coordinates.setProperty(properties.getProperty("coordinate"), "");
                                Path pomFile = paths.get("../scan")
                                        .resolve(POM)
                                        .resolve(properties.getProperty("path"))
                                        .resolve("pom.xml");
                                coordinates.setProperty(properties.getProperty("pom"),
                                        context.next().relativize(pomFile).toString());
                                try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(IDENTITY))) {
                                    coordinates.store(writer, null);
                                }
                                Properties dependencies = new SequencedProperties();
                                if (!properties.getProperty("dependencies").isEmpty()) {
                                    for (String dependency : properties.getProperty("dependencies").split(",")) {
                                        dependencies.setProperty(dependency, "");
                                    }
                                }
                                for (String scope : List.of(MultiProjectModule.COMPILE, MultiProjectModule.RUNTIME)) {
                                    Path target = Files.createDirectories(context.next().resolve(scope));
                                    try (BufferedWriter writer = Files.newBufferedWriter(target.resolve(BuildStep.REQUIRES))) {
                                        dependencies.store(writer, null);
                                    }
                                }
                                Properties versions = new SequencedProperties();
                                String managed = properties.getProperty("managedDependencies", "");
                                if (!managed.isEmpty()) {
                                    for (String entry : managed.split(",")) {
                                        int split = entry.lastIndexOf('/');
                                        versions.setProperty(entry.substring(0, split), entry.substring(split + 1));
                                    }
                                }
                                if (!versions.isEmpty()) {
                                    for (String scope : List.of(MultiProjectModule.COMPILE, MultiProjectModule.RUNTIME)) {
                                        Path target = Files.createDirectories(context.next().resolve(scope));
                                        try (BufferedWriter writer = Files.newBufferedWriter(target.resolve(BuildStep.VERSIONS))) {
                                            versions.store(writer, null);
                                        }
                                    }
                                }
                                Javac.writeRelease(context.next(), properties.getProperty("release"));
                                Properties metadata = extractMetadata(pomFile);
                                String[] coordinateParts = properties.getProperty("coordinate").split("/");
                                if (coordinateParts.length == 6 && "tests".equals(coordinateParts[4])) {
                                    metadata.setProperty("project.test", coordinateParts[2]);
                                }
                                if (!metadata.isEmpty()) {
                                    try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.METADATA))) {
                                        metadata.store(writer, null);
                                    }
                                }
                                return CompletableFuture.completedStage(new BuildStepResult(true));
                            });
                        }
                    });
                }
            }
        }, "scan", "prepare");
    }

    private static Properties extractMetadata(Path pomFile) throws IOException {
        Properties result = new SequencedProperties();
        if (!Files.isRegularFile(pomFile)) {
            return result;
        }
        Document document;
        try (InputStream stream = Files.newInputStream(pomFile)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            document = factory.newDocumentBuilder().parse(stream);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(e);
        }
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
            switch (name) {
                case "name" -> result.setProperty("project.name", node.getTextContent().trim());
                case "description" -> result.setProperty("project.description", node.getTextContent().trim());
                case "url" -> result.setProperty("project.url", node.getTextContent().trim());
                case "licenses" -> {
                    Element first = firstChild(node, "license");
                    if (first != null) {
                        copyChildText(first, "name", result, "license.name");
                        copyChildText(first, "url", result, "license.url");
                    }
                }
                case "developers" -> {
                    Element first = firstChild(node, "developer");
                    if (first != null) {
                        copyChildText(first, "id", result, "developer.id");
                        copyChildText(first, "name", result, "developer.name");
                        copyChildText(first, "email", result, "developer.email");
                    }
                }
                case "scm" -> {
                    copyChildText(node, "connection", result, "scm.connection");
                    copyChildText(node, "developerConnection", result, "scm.developerConnection");
                    copyChildText(node, "url", result, "scm.url");
                }
                default -> {
                }
            }
        }
        return result;
    }

    private static Element firstChild(Node parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static void copyChildText(Node parent, String localName, Properties target, String key) {
        Element child = firstChild(parent, localName);
        if (child != null) {
            target.setProperty(key, child.getTextContent().trim());
        }
    }

    private record Scan(Path root) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path poms = Files.createDirectory(context.next().resolve(POM));
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().equals("pom.xml")) {
                        Path target = poms.resolve(root.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.createLink(target, file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Files.exists(dir.resolve(BuildExecutor.BUILD_MARKER))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            // depends on root but also all files within the root. The easiest is to trigger
            // this build step each time to scan for possible changes of POMs and analyze them
            // in a subsequent (cached) build step.
            return true;
        }
    }

    private static class Prepare implements BuildStep {

        private final String prefix;
        private final MavenPomResolver resolver;
        private final transient MavenRepository repository;

        private Prepare(String prefix, MavenPomResolver resolver, MavenRepository repository) {
            this.prefix = prefix;
            this.resolver = resolver;
            this.repository = repository;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path maven = Files.createDirectory(context.next().resolve(MAVEN));
            for (Map.Entry<Path, MavenLocalPom> entry : resolver.local(executor,
                    repository,
                    arguments.get("scan")
                            .folder()
                            .resolve(POM)).entrySet()) {
                if (Objects.equals("pom", entry.getValue().packaging())) {
                    continue;
                }
                String coordinate = prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/" + (entry.getValue().packaging() == null ? "jar" : entry.getValue().packaging())
                        + "/" + entry.getValue().version();
                Properties module = new SequencedProperties();
                module.setProperty("coordinate", coordinate);
                module.setProperty("pom", prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/pom"
                        + "/" + entry.getValue().version());
                module.setProperty("path", entry.getKey().toString());
                module.setProperty("groupId", entry.getValue().groupId());
                module.setProperty("artifactId", entry.getValue().artifactId());
                module.setProperty("version", entry.getValue().version());
                module.setProperty("type", entry.getValue().packaging() == null
                        ? "jar"
                        : entry.getValue().packaging());
                if (entry.getValue().release() != null) {
                    module.setProperty("release", entry.getValue().release());
                }
                module.setProperty("dependencies", entry.getValue().dependencies() == null ? "" : entry.getValue().dependencies().entrySet().stream()
                        .filter(dep -> Set.of(MavenDependencyScope.COMPILE, MavenDependencyScope.PROVIDED).contains(dep.getValue().scope()))
                        .map(dep -> prefix
                                + "/" + dep.getKey().groupId()
                                + "/" + dep.getKey().artifactId()
                                + "/" + (dep.getKey().type() == null ? "jar" : dep.getKey().type())
                                + (dep.getKey().classifier() == null ? "" : "/" + dep.getKey().classifier())
                                + "/" + dep.getValue().version())
                        .collect(Collectors.joining(",")));
                module.setProperty("managedDependencies", entry.getValue().managedDependencies() == null ? "" : entry.getValue().managedDependencies().entrySet().stream()
                        .map(dep -> prefix
                                + "/" + dep.getKey().groupId()
                                + "/" + dep.getKey().artifactId()
                                + "/" + (dep.getKey().type() == null ? "jar" : dep.getKey().type())
                                + (dep.getKey().classifier() == null ? "" : "/" + dep.getKey().classifier())
                                + "/" + dep.getValue().version())
                        .collect(Collectors.joining(",")));
                module.setProperty("sources", entry.getValue().sourceDirectory() == null
                        ? "src/main/java"
                        : entry.getValue().sourceDirectory());
                module.setProperty("resources", entry.getValue().resourceDirectories() == null
                        ? "src/main/resources"
                        : entry.getValue().resourceDirectories().stream().sorted().collect(Collectors.joining(",")));
                try (Writer writer = Files.newBufferedWriter(maven.resolve("module-" + URLEncoder.encode(
                        entry.getKey().toString(),
                        StandardCharsets.UTF_8) + ".properties"))) {
                    module.store(writer, null);
                }
                Properties testModule = new SequencedProperties();
                testModule.setProperty("coordinate", prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/" + (entry.getValue().packaging() == null ? "jar" : entry.getValue().packaging())
                        + "/tests"
                        + "/" + entry.getValue().version());
                testModule.setProperty("pom", prefix
                        + "/" + entry.getValue().groupId()
                        + "/" + entry.getValue().artifactId()
                        + "/pom"
                        + "/" + entry.getValue().version());
                testModule.setProperty("path", entry.getKey().toString());
                if (entry.getValue().release() != null) {
                    testModule.setProperty("release", entry.getValue().release());
                }
                String dependencies = entry.getValue().dependencies() == null ? "" : entry.getValue().dependencies().entrySet().stream()
                        .filter(dep -> Set.of(MavenDependencyScope.TEST, MavenDependencyScope.RUNTIME).contains(dep.getValue().scope()))
                        .map(dep -> prefix
                                + "/" + dep.getKey().groupId()
                                + "/" + dep.getKey().artifactId()
                                + "/" + (dep.getKey().type() == null ? "jar" : dep.getKey().type())
                                + (dep.getKey().classifier() == null ? "" : "/" + dep.getKey().classifier())
                                + "/" + dep.getValue().version())
                        .collect(Collectors.joining(","));
                testModule.setProperty("dependencies", dependencies.isEmpty()
                        ? coordinate
                        : dependencies + "," + coordinate);
                testModule.setProperty("managedDependencies", entry.getValue().managedDependencies() == null ? "" : entry.getValue().managedDependencies().entrySet().stream()
                        .map(dep -> prefix
                                + "/" + dep.getKey().groupId()
                                + "/" + dep.getKey().artifactId()
                                + "/" + (dep.getKey().type() == null ? "jar" : dep.getKey().type())
                                + (dep.getKey().classifier() == null ? "" : "/" + dep.getKey().classifier())
                                + "/" + dep.getValue().version())
                        .collect(Collectors.joining(",")));
                testModule.setProperty("sources", entry.getValue().testSourceDirectory() == null
                        ? "src/test/java"
                        : entry.getValue().testSourceDirectory());
                testModule.setProperty("resources", entry.getValue().testResourceDirectories() == null
                        ? "src/test/resources"
                        : entry.getValue().testResourceDirectories().stream().sorted().collect(Collectors.joining(",")));
                try (Writer writer = Files.newBufferedWriter(maven.resolve("test-module-" + URLEncoder.encode(
                        entry.getKey().toString(),
                        StandardCharsets.UTF_8) + ".properties"))) {
                    testModule.store(writer, null);
                }
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
