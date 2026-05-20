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
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.project.DependencyScope;
import build.jenesis.step.Assign;
import build.jenesis.step.Bind;
import build.jenesis.step.Inventory;
import build.jenesis.step.Javac;

import static build.jenesis.BuildStep.IDENTITY;
import static build.jenesis.project.MultiProjectModule.ASSIGN;
import static build.jenesis.project.MultiProjectModule.COORDINATES;
import static build.jenesis.project.MultiProjectModule.DEPENDENCIES;
import static build.jenesis.project.MultiProjectModule.MANIFESTS;
import static build.jenesis.project.MultiProjectModule.MODULE;
import static build.jenesis.project.MultiProjectModule.PREPARE;
import static build.jenesis.project.MultiProjectModule.PRODUCE;
import static build.jenesis.project.MultiProjectModule.SOURCES;

public class MavenProject implements BuildExecutorModule {

    public static final String POM = "pom/", MAVEN = "maven/";

    private static final String SCAN = "scan";
    private static final String SIBLING_MODULE_PREFIX = MultiProjectModule.MODULE + "-";

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

    public static BuildExecutorModule make(Path root, MultiProjectAssembler<? super MavenModuleDescriptor> assembler) {
        return make(root, "maven", new MavenDefaultRepository(), new MavenPomResolver(), false, assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           boolean strictPinning,
                                           MultiProjectAssembler<? super MavenModuleDescriptor> assembler) {
        return make(root, "maven", new MavenDefaultRepository(), new MavenPomResolver(), strictPinning, assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           MavenRepository mavenRepository,
                                           MavenPomResolver mavenResolver,
                                           MultiProjectAssembler<? super MavenModuleDescriptor> assembler) {
        return make(root, prefix, mavenRepository, mavenResolver, false, assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           MavenRepository mavenRepository,
                                           MavenPomResolver mavenResolver,
                                           boolean strictPinning,
                                           MultiProjectAssembler<? super MavenModuleDescriptor> assembler) {
        return new MultiProjectModule(new MavenProject(root, prefix, mavenRepository, mavenResolver),
                identifier -> Optional.of(identifier.substring(0, identifier.indexOf('/'))),
                _ -> (name, dependencies, _) -> (buildExecutor, inherited) -> {
                    Map<String, Repository> mergedRepositories = Repository.prepend(Map.of(prefix, mavenRepository),
                            Repository.ofProperties(BuildStep.IDENTITY,
                                    inherited.entrySet().stream()
                                            .filter(entry ->
                                                    entry.getKey().startsWith(PREVIOUS + SIBLING_MODULE_PREFIX)
                                                            && entry.getKey().endsWith("/" + ASSIGN))
                                            .map(Map.Entry::getValue)
                                            .toList(),
                                    (folder, file) -> folder.resolve(file).normalize().toUri(),
                                    null));
                    Map<String, Resolver> resolverMap = Map.of(prefix, mavenResolver);
                    for (DependencyScope scope : DependencyScope.values()) {
                        buildExecutor.addModule(scope.label(), (scopeExec, scopeInherited) -> {
                            scopeExec.addStep(PREPARE,
                                    new MultiProjectDependencies(
                                            identifier -> identifier.contains("/" + MultiProjectModule.IDENTIFIER + "/" + name + "/"),
                                            scope),
                                    scopeInherited.sequencedKeySet());
                            scopeExec.addModule(DEPENDENCIES,
                                    new DependenciesModule(mergedRepositories, resolverMap, scope == DependencyScope.COMPILE, strictPinning),
                                    PREPARE);
                        }, inherited.sequencedKeySet());
                    }
                    SequencedMap<String, String> produceDeps = new LinkedHashMap<>();
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + SOURCES, SOURCES);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS, MANIFESTS);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES, COORDINATES);
                    for (DependencyScope scope : List.of(DependencyScope.COMPILE, DependencyScope.RUNTIME)) {
                        String resolved = scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.RESOLVED;
                        String artifacts = scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS;
                        produceDeps.put(resolved, resolved);
                        produceDeps.put(artifacts, artifacts);
                    }
                    for (String key : inherited.sequencedKeySet()) {
                        produceDeps.putIfAbsent(key, key);
                    }
                    buildExecutor.addModule(PRODUCE,
                            assembler.apply(new MavenModuleDescriptor(name, dependencies.sequencedKeySet()),
                                    mergedRepositories,
                                    resolverMap),
                            produceDeps);
                    buildExecutor.addStep(ASSIGN,
                            new Assign(),
                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES,
                            PRODUCE);
                    buildExecutor.addStep(MultiProjectModule.INVENTORY,
                            new Inventory(),
                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS,
                            ASSIGN,
                            PRODUCE,
                            DependencyScope.RUNTIME.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS);
                });
    }

    @Override
    public Optional<String> resolve(String path) {
        String wrapped = MultiProjectModule.MODULE + "/";
        if (path.startsWith(wrapped)) {
            return Optional.of(path.substring(wrapped.length()));
        }
        return Optional.empty();
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        if (!Files.exists(root.resolve("pom.xml"))) {
            return;
        }
        buildExecutor.addStep(SCAN, new Scan(root));
        buildExecutor.addStep(PREPARE, new Prepare(prefix, resolver, repository), SCAN);
        buildExecutor.addModule(MODULE, (modules, paths) -> {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(
                    paths.get(PREVIOUS + PREPARE).resolve(MAVEN),
                    "*.properties")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    modules.addModule(name.substring(0, name.length() - 11), (module, modInherited) -> {
                        SequencedProperties properties = SequencedProperties.ofFiles(file);
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
                            module.addStep(COORDINATES, (_, context, _) -> {
                                SequencedProperties coordinates = new SequencedProperties();
                                coordinates.setProperty(properties.getProperty("coordinate"), "");
                                Path pomFile = paths.get(PREVIOUS + SCAN)
                                        .resolve(POM)
                                        .resolve(properties.getProperty("path"))
                                        .resolve("pom.xml");
                                coordinates.setProperty(properties.getProperty("pom"),
                                        context.next().relativize(pomFile).toString().replace(File.separatorChar, '/'));
                                coordinates.store(context.next().resolve(IDENTITY));
                                return CompletableFuture.completedStage(new BuildStepResult(true));
                            });
                            module.addStep(MANIFESTS, (_, context, manifestArgs) -> {
                                Path pomFile = paths.get(PREVIOUS + SCAN)
                                        .resolve(POM)
                                        .resolve(properties.getProperty("path"))
                                        .resolve("pom.xml");
                                String[] coordinateParts = properties.getProperty("coordinate").split("/");
                                String testsOf = coordinateParts.length == 6 && "tests".equals(coordinateParts[4])
                                        ? coordinateParts[2]
                                        : null;
                                SequencedProperties requires = new SequencedProperties();
                                SequencedProperties scopes = new SequencedProperties();
                                String compile = properties.getProperty("dependencies.compile", "");
                                String provided = properties.getProperty("dependencies.provided", "");
                                String runtime = properties.getProperty("dependencies.runtime", "");
                                String test = properties.getProperty("dependencies.test", "");
                                String checksums = properties.getProperty("checksums", "");
                                Map<String, String> checksumByCoordinate = new LinkedHashMap<>();
                                for (String entry : checksums.isEmpty() ? new String[0] : checksums.split(",")) {
                                    int split = entry.indexOf('=');
                                    if (split > 0) {
                                        checksumByCoordinate.put(entry.substring(0, split), entry.substring(split + 1));
                                    }
                                }
                                String compileAndRuntime = DependencyScope.COMPILE.name() + "," + DependencyScope.RUNTIME.name();
                                for (String dependency : compile.isEmpty() ? new String[0] : compile.split(",")) {
                                    requires.setProperty(dependency, checksumByCoordinate.getOrDefault(dependency, ""));
                                    scopes.setProperty(dependency, compileAndRuntime);
                                }
                                for (String dependency : provided.isEmpty() ? new String[0] : provided.split(",")) {
                                    requires.setProperty(dependency, checksumByCoordinate.getOrDefault(dependency, ""));
                                    scopes.setProperty(dependency, DependencyScope.COMPILE.name());
                                }
                                for (String dependency : runtime.isEmpty() ? new String[0] : runtime.split(",")) {
                                    requires.setProperty(dependency, checksumByCoordinate.getOrDefault(dependency, ""));
                                    scopes.setProperty(dependency, DependencyScope.RUNTIME.name());
                                }
                                for (String dependency : test.isEmpty() ? new String[0] : test.split(",")) {
                                    requires.setProperty(dependency, checksumByCoordinate.getOrDefault(dependency, ""));
                                    scopes.setProperty(dependency, compileAndRuntime);
                                }
                                requires.store(context.next().resolve(BuildStep.REQUIRES));
                                scopes.store(context.next().resolve(BuildStep.SCOPES));
                                SequencedProperties versions = new SequencedProperties();
                                String managed = properties.getProperty("managedDependencies", "");
                                if (!managed.isEmpty()) {
                                    for (String entry : managed.split(",")) {
                                        int split = entry.indexOf('=');
                                        versions.setProperty(entry.substring(0, split), entry.substring(split + 1));
                                    }
                                }
                                if (!versions.isEmpty()) {
                                    versions.store(context.next().resolve(BuildStep.VERSIONS));
                                }
                                Javac.writeRelease(context.next(), properties.getProperty("release"));
                                SequencedProperties descriptor = new SequencedProperties();
                                descriptor.setProperty("path", properties.getProperty("path"));
                                if (testsOf != null) {
                                    descriptor.setProperty("test", testsOf);
                                }
                                String mainClass = properties.getProperty("mainClass");
                                if (mainClass != null && testsOf == null) {
                                    descriptor.setProperty("main", mainClass);
                                }
                                descriptor.store(context.next().resolve(BuildStep.MODULE));
                                SequencedProperties metadata = new SequencedProperties();
                                metadata.setProperty("project", properties.getProperty("groupId"));
                                metadata.setProperty("artifact", properties.getProperty("artifactId"));
                                metadata.setProperty("version", properties.getProperty("version"));
                                extractMetadata(pomFile).forEach(metadata::put);
                                for (BuildStepArgument argument : manifestArgs.values()) {
                                    Path upstream = argument.folder().resolve(BuildStep.METADATA);
                                    if (Files.isRegularFile(upstream)) {
                                        SequencedProperties.ofFiles(upstream).forEach(metadata::put);
                                    }
                                }
                                metadata.store(context.next().resolve(BuildStep.METADATA));
                                return CompletableFuture.completedStage(new BuildStepResult(true));
                            }, modInherited.sequencedKeySet());
                        }
                    }, paths.sequencedKeySet().stream());
                }
            }
        }, Stream.concat(Stream.of(SCAN, PREPARE), inherited.sequencedKeySet().stream()));
    }

    private static SequencedProperties extractMetadata(Path pomFile) throws IOException {
        SequencedProperties result = new SequencedProperties();
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
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
            switch (name) {
                case "name" -> result.setProperty("name", node.getTextContent().trim());
                case "description" -> result.setProperty("description", node.getTextContent().trim());
                case "url" -> result.setProperty("url", node.getTextContent().trim());
                case "licenses" -> {
                    Element first = firstChild(node, "license");
                    if (first != null) {
                        copyChildText(first, "name", result, "license.name");
                        copyChildText(first, "url", result, "license.url");
                    }
                }
                case "developers" -> {
                    NodeList developers = node.getChildNodes();
                    for (int developerIndex = 0; developerIndex < developers.getLength(); developerIndex++) {
                        Node devNode = developers.item(developerIndex);
                        if (devNode.getNodeType() != Node.ELEMENT_NODE) {
                            continue;
                        }
                        String devName = devNode.getLocalName() == null ? devNode.getNodeName() : devNode.getLocalName();
                        if (!"developer".equals(devName)) {
                            continue;
                        }
                        Element developer = (Element) devNode;
                        Element idElement = firstChild(developer, "id");
                        if (idElement == null) {
                            continue;
                        }
                        String id = idElement.getTextContent().trim();
                        if (id.isEmpty()) {
                            continue;
                        }
                        copyChildText(developer, "name", result, "developer." + id + ".name");
                        copyChildText(developer, "email", result, "developer." + id + ".email");
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
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static void copyChildText(Node parent, String localName, SequencedProperties target, String key) {
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
                    arguments.get(SCAN)
                            .folder()
                            .resolve(POM)).entrySet()) {
                if (entry.getValue().packaging() != null && !"jar".equals(entry.getValue().packaging())) {
                    continue;
                }
                MavenDependencyKey selfKey = new MavenDependencyKey(
                        entry.getValue().groupId(), entry.getValue().artifactId(), "jar", null);
                String coordinate = selfKey.coordinate(prefix, entry.getValue().version());
                MavenDependencyKey selfPom = new MavenDependencyKey(
                        entry.getValue().groupId(), entry.getValue().artifactId(), "pom", null);
                String relativePath = entry.getKey().toString().replace(File.separatorChar, '/');
                SequencedProperties module = new SequencedProperties();
                module.setProperty("coordinate", coordinate);
                module.setProperty("pom", selfPom.coordinate(prefix, entry.getValue().version()));
                module.setProperty("path", relativePath);
                module.setProperty("groupId", entry.getValue().groupId());
                module.setProperty("artifactId", entry.getValue().artifactId());
                module.setProperty("version", entry.getValue().version());
                if (entry.getValue().release() != null) {
                    module.setProperty("release", entry.getValue().release());
                }
                if (entry.getValue().mainClass() != null) {
                    module.setProperty("mainClass", entry.getValue().mainClass());
                }
                for (MavenDependencyScope scope : List.of(
                        MavenDependencyScope.COMPILE,
                        MavenDependencyScope.PROVIDED,
                        MavenDependencyScope.RUNTIME)) {
                    module.setProperty("dependencies." + scope.name().toLowerCase(Locale.ROOT),
                            entry.getValue().dependencies() == null ? "" : entry.getValue().dependencies().entrySet().stream()
                                    .filter(dep -> dep.getValue().scope() == scope)
                                    .map(dep -> dep.getKey().coordinate(prefix, dep.getValue().version()))
                                    .collect(Collectors.joining(",")));
                }
                module.setProperty("managedDependencies",
                        entry.getValue().managedDependencies() == null ? "" : entry.getValue().managedDependencies().entrySet().stream()
                                .map(dep -> dep.getKey().coordinate(prefix, null)
                                        + "=" + dep.getValue().version()
                                        + (dep.getValue().checksum() == null ? "" : " " + dep.getValue().checksum()))
                                .collect(Collectors.joining(",")));
                module.setProperty("checksums",
                        entry.getValue().dependencies() == null ? "" : entry.getValue().dependencies().entrySet().stream()
                                .filter(dep -> dep.getValue().checksum() != null
                                        && dep.getValue().scope() != MavenDependencyScope.TEST)
                                .map(dep -> dep.getKey().coordinate(prefix, dep.getValue().version())
                                        + "=" + dep.getValue().checksum())
                                .collect(Collectors.joining(",")));
                module.setProperty("sources", entry.getValue().sourceDirectory() == null
                        ? "src/main/java"
                        : entry.getValue().sourceDirectory());
                module.setProperty("resources", entry.getValue().resourceDirectories() == null
                        ? "src/main/resources"
                        : entry.getValue().resourceDirectories().stream().sorted().collect(Collectors.joining(",")));
                module.store(maven.resolve("module-" + BuildExecutorModule.encode(relativePath) + ".properties"));
                SequencedProperties testModule = new SequencedProperties();
                MavenDependencyKey testSelfKey = new MavenDependencyKey(
                        entry.getValue().groupId(), entry.getValue().artifactId(), "jar", "tests");
                testModule.setProperty("coordinate", testSelfKey.coordinate(prefix, entry.getValue().version()));
                testModule.setProperty("pom", selfPom.coordinate(prefix, entry.getValue().version()));
                testModule.setProperty("path", relativePath);
                testModule.setProperty("groupId", entry.getValue().groupId());
                testModule.setProperty("artifactId", entry.getValue().artifactId());
                testModule.setProperty("version", entry.getValue().version());
                if (entry.getValue().release() != null) {
                    testModule.setProperty("release", entry.getValue().release());
                }
                String testDependencies = entry.getValue().dependencies() == null
                        ? ""
                        : entry.getValue().dependencies().entrySet().stream()
                                .filter(dep -> dep.getValue().scope() == MavenDependencyScope.TEST)
                                .map(dep -> dep.getKey().coordinate(prefix, dep.getValue().version()))
                                .collect(Collectors.joining(","));
                testModule.setProperty("dependencies.test", testDependencies.isEmpty()
                        ? coordinate
                        : testDependencies + "," + coordinate);
                testModule.setProperty("managedDependencies",
                        entry.getValue().managedDependencies() == null ? "" : entry.getValue().managedDependencies().entrySet().stream()
                                .map(dep -> dep.getKey().coordinate(prefix, null)
                                        + "=" + dep.getValue().version()
                                        + (dep.getValue().checksum() == null ? "" : " " + dep.getValue().checksum()))
                                .collect(Collectors.joining(",")));
                testModule.setProperty("checksums",
                        entry.getValue().dependencies() == null ? "" : entry.getValue().dependencies().entrySet().stream()
                                .filter(dep -> dep.getValue().checksum() != null
                                        && dep.getValue().scope() == MavenDependencyScope.TEST)
                                .map(dep -> dep.getKey().coordinate(prefix, dep.getValue().version())
                                        + "=" + dep.getValue().checksum())
                                .collect(Collectors.joining(",")));
                testModule.setProperty("sources", entry.getValue().testSourceDirectory() == null
                        ? "src/test/java"
                        : entry.getValue().testSourceDirectory());
                testModule.setProperty("resources", entry.getValue().testResourceDirectories() == null
                        ? "src/test/resources"
                        : entry.getValue().testResourceDirectories().stream().sorted().collect(Collectors.joining(",")));
                testModule.store(maven.resolve("test-module-" + BuildExecutorModule.encode(relativePath) + ".properties"));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    public record MavenModuleDescriptor(String name, SequencedSet<String> dependencies) implements ModuleDescriptor {

        @Override
        public String sources() {
            return BuildExecutorModule.PREVIOUS + SOURCES;
        }

        @Override
        public String manifests() {
            return BuildExecutorModule.PREVIOUS + MANIFESTS;
        }

        @Override
        public String coordinates() {
            return BuildExecutorModule.PREVIOUS + COORDINATES;
        }

        @Override
        public String artifacts(DependencyScope scope) {
            return BuildExecutorModule.PREVIOUS + scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS;
        }

        @Override
        public String resolved(DependencyScope scope) {
            return BuildExecutorModule.PREVIOUS + scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.RESOLVED;
        }
    }
}
