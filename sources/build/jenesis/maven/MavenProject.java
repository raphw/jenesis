package build.jenesis.maven;

import module java.base;
import build.jenesis.Pinning;
import module java.xml;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Platform;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.AssemblyDescriptor;
import build.jenesis.project.ProjectModule;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.Assign;
import build.jenesis.step.Bind;
import build.jenesis.step.Dependencies;
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
import static java.util.Objects.requireNonNull;

public class MavenProject implements BuildExecutorModule {

    public static final String POM = "pom/", MAVEN = "maven/";

    private static final String SCAN = "scan";
    private static final String SIBLING_MODULE_PREFIX = MultiProjectModule.MODULE + "-";

    private final Path root;
    private final String group;
    private final String prefix;
    private final MavenRepository repository;
    private final MavenResolver resolver;
    private final Platform platform;

    public MavenProject(Path root, String prefix, MavenRepository repository, MavenResolver resolver) {
        this(root, "main", prefix, repository, resolver, new Platform());
    }

    private MavenProject(Path root,
                         String group,
                         String prefix,
                         MavenRepository repository,
                         MavenResolver resolver,
                         Platform platform) {
        this.root = root;
        this.group = group;
        this.prefix = prefix;
        this.repository = repository;
        this.resolver = resolver;
        this.platform = platform;
    }

    public MavenProject group(String group) {
        return new MavenProject(root, group, prefix, repository, resolver, platform);
    }

    public MavenProject platform(Platform platform) {
        return new MavenProject(root, group, prefix, repository, resolver, platform);
    }

    public static BuildExecutorModule make(Path root,
                                           MultiProjectAssembler<? super MavenModuleDescriptor> assembler) {
        return make(root,
                "main",
                "maven",
                Map.of("maven", new MavenDefaultRepository()),
                Map.of("maven", new MavenPomResolver()),
                null,
                false,
                assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           String group,
                                           String prefix,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           Pinning pinning,
                                           boolean printDependencies,
                                           MultiProjectAssembler<? super MavenModuleDescriptor> assembler) {
        MavenRepository repository = MavenRepository.of(requireNonNull(repositories.get(prefix)));
        MavenResolver resolver = MavenResolver.of(resolvers.get(prefix));
        return new MultiProjectModule(new MavenProject(root, prefix, repository, resolver).group(group),
                identifier -> Optional.of(identifier.substring(0, identifier.indexOf('/'))),
                _ -> (name, dependencies, _) -> {
                    AssemblyDescriptor packaging = assembler.apply(
                            new MavenModuleDescriptor(name, dependencies.sequencedKeySet(), Collections.emptyNavigableSet()),
                            repositories,
                            resolvers);
                    AssemblyDescriptor assembly = new AssemblyDescriptor((buildExecutor, inherited) -> {
                    Map<String, Repository> mergedRepositories = Repository.prepend(repositories,
                            Repository.ofProperties(BuildStep.IDENTITY,
                                    inherited.entrySet().stream()
                                            .filter(entry ->
                                                    (entry.getKey().startsWith(PREVIOUS + SIBLING_MODULE_PREFIX)
                                                            || entry.getKey().startsWith(PREVIOUS + "test-" + SIBLING_MODULE_PREFIX))
                                                            && entry.getKey().endsWith("/" + ASSIGN))
                                            .map(Map.Entry::getValue)
                                            .toList(),
                                    (folder, file) -> folder.resolve(file).normalize().toUri(),
                                    null));
                    buildExecutor.addModule(DEPENDENCIES, (depExec, depInherited) -> {
                        depExec.addStep(PREPARE,
                                new MultiProjectDependencies(
                                        identifier -> identifier.contains("/" + MultiProjectModule.IDENTIFIER + "/" + name + "/")),
                                depInherited.sequencedKeySet());
                        depExec.addStep(Dependencies.ARTIFACTS,
                                new Dependencies(mergedRepositories, resolvers).pinning(pinning).printing(printDependencies),
                                PREPARE);
                    }, inherited.sequencedKeySet());
                    SequencedMap<String, String> produceDeps = new LinkedHashMap<>();
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + SOURCES, SOURCES);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS, MANIFESTS);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES, COORDINATES);
                    SequencedSet<String> resources = new LinkedHashSet<>();
                    String resourcesPrefix = MultiProjectModule.IDENTIFIER_PATH + name + "/resources-";
                    for (String key : inherited.sequencedKeySet()) {
                        if (key.startsWith(resourcesPrefix)) {
                            String synonym = key.substring(MultiProjectModule.IDENTIFIER_PATH.length() + name.length() + 1);
                            produceDeps.put(key, synonym);
                            resources.add(BuildExecutorModule.PREVIOUS + synonym);
                        }
                    }
                    produceDeps.put(DEPENDENCIES + "/" + Dependencies.ARTIFACTS, DEPENDENCIES + "/" + Dependencies.ARTIFACTS);
                    for (String key : inherited.sequencedKeySet()) {
                        produceDeps.putIfAbsent(key, key);
                    }
                    buildExecutor.addModule(PRODUCE,
                            assembler.apply(new MavenModuleDescriptor(name, dependencies.sequencedKeySet(), resources),
                                    mergedRepositories,
                                    resolvers).build(),
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
                            DEPENDENCIES + "/" + Dependencies.ARTIFACTS);
                    });
                    for (Map.Entry<String, BuildExecutorModule> phase : packaging.tail().entrySet()) {
                        assembly = assembly.then(phase.getKey(), phase.getValue());
                    }
                    return assembly;
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
        String group = this.group;
        Platform platform = this.platform;
        buildExecutor.addStep(SCAN, new Scan(root));
        buildExecutor.addStep(PREPARE, new Prepare(prefix, resolver, repository), SCAN);
        buildExecutor.addModule(MODULE, (modules, paths) -> {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(
                    paths.get(PREVIOUS + PREPARE).resolve(MAVEN),
                    "*.properties")) {
                List<Path> descriptors = new ArrayList<>();
                files.forEach(descriptors::add);
                descriptors.sort(null);
                for (Path file : descriptors) {
                    String name = file.getFileName().toString();
                    modules.addModule(name.substring(0, name.length() - 11), (module, modInherited) -> {
                        SequencedProperties properties = SequencedProperties.ofFiles(file);
                        boolean active = false;
                        Path base = root.resolve(properties.getProperty("path"));
                        String sourcesProperty = properties.getProperty("sources");
                        Path sources = sourcesProperty.isEmpty() ? null : base.resolve(sourcesProperty);
                        if (sources != null && Files.exists(sources)) {
                            active = true;
                        }
                        int index = 0;
                        for (int resourceIndex = 0; ; resourceIndex++) {
                            String resource = properties.getProperty("resources." + resourceIndex);
                            if (resource == null) {
                                break;
                            }
                            Path resources = base.resolve(resource);
                            if (Files.exists(resources)) {
                                module.addSource("resources-" + ++index, Bind.asResources(), resources);
                                active = true;
                            }
                        }
                        if (active) {
                            module.addSource("sources", Bind.asSources(), sources == null ? base : sources);
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
                                for (String dependency : compile.isEmpty() ? new String[0] : compile.split(",")) {
                                    String value = checksumByCoordinate.getOrDefault(dependency, "");
                                    requires.setProperty(group + "/compile/" + dependency, value);
                                    requires.setProperty(group + "/runtime/" + dependency, value);
                                }
                                for (String dependency : provided.isEmpty() ? new String[0] : provided.split(",")) {
                                    requires.setProperty(group + "/compile/" + dependency, checksumByCoordinate.getOrDefault(dependency, ""));
                                }
                                for (String dependency : runtime.isEmpty() ? new String[0] : runtime.split(",")) {
                                    requires.setProperty(group + "/runtime/" + dependency, checksumByCoordinate.getOrDefault(dependency, ""));
                                }
                                for (String dependency : test.isEmpty() ? new String[0] : test.split(",")) {
                                    String value = checksumByCoordinate.getOrDefault(dependency, "");
                                    requires.setProperty(group + "/compile/" + dependency, value);
                                    requires.setProperty(group + "/runtime/" + dependency, value);
                                }
                                requires.store(context.next().resolve(BuildStep.REQUIRES));
                                SequencedProperties exclusionsProperties = new SequencedProperties();
                                for (String key : requires.stringPropertyNames()) {
                                    int scopeSlash = key.indexOf('/', key.indexOf('/') + 1);
                                    String exclusion = properties.getProperty("exclusions." + key.substring(scopeSlash + 1));
                                    if (exclusion != null) {
                                        exclusionsProperties.setProperty(key, exclusion);
                                    }
                                }
                                if (!exclusionsProperties.isEmpty()) {
                                    exclusionsProperties.store(context.next().resolve(BuildStep.EXCLUSIONS));
                                }
                                SequencedProperties versions = new SequencedProperties();
                                String managed = properties.getProperty("managedDependencies", "");
                                if (!managed.isEmpty()) {
                                    for (String entry : managed.split(",")) {
                                        int split = entry.indexOf('=');
                                        String coord = entry.substring(0, split), version = entry.substring(split + 1);
                                        versions.setProperty(group + "/" + coord, version);
                                    }
                                }
                                String qualified = properties.getProperty("qualifiedDependencies", "");
                                if (!qualified.isEmpty()) {
                                    SequencedMap<String, String> unguarded = new LinkedHashMap<>();
                                    SequencedMap<String, SequencedMap<String, String>> variants = new LinkedHashMap<>();
                                    for (String entry : qualified.split("\t")) {
                                        int split = entry.indexOf('=');
                                        String key = entry.substring(0, split), value = entry.substring(split + 1);
                                        int bracket = key.indexOf('[');
                                        if (bracket < 0) {
                                            unguarded.put(key, value);
                                        } else {
                                            variants.computeIfAbsent(key.substring(0, bracket), _ -> new LinkedHashMap<>())
                                                    .put(key.substring(bracket + 1, key.length() - 1), value);
                                        }
                                    }
                                    for (Map.Entry<String, SequencedMap<String, String>> variant : variants.entrySet()) {
                                        String selected = platform.select(variant.getKey(),
                                                unguarded.get(variant.getKey()),
                                                variant.getValue());
                                        if (selected != null) {
                                            unguarded.put(variant.getKey(), selected);
                                        }
                                    }
                                    unguarded.forEach(versions::setProperty);
                                }
                                if (!versions.isEmpty()) {
                                    versions.store(context.next().resolve(BuildStep.VERSIONS));
                                }
                                Javac.writeRelease(context.next(), properties.getProperty("release"));
                                SequencedProperties descriptor = new SequencedProperties();
                                descriptor.setProperty("path", properties.getProperty("path"));
                                descriptor.setProperty("modular", "false");
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
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
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
                    NodeList licenses = node.getChildNodes();
                    for (int licenseIndex = 0; licenseIndex < licenses.getLength(); licenseIndex++) {
                        Node licenseNode = licenses.item(licenseIndex);
                        if (licenseNode.getNodeType() != Node.ELEMENT_NODE) {
                            continue;
                        }
                        String licenseName = licenseNode.getLocalName() == null
                                ? licenseNode.getNodeName()
                                : licenseNode.getLocalName();
                        if (!"license".equals(licenseName)) {
                            continue;
                        }
                        Element license = (Element) licenseNode;
                        Element nameElement = firstChild(license, "name");
                        if (nameElement == null) {
                            continue;
                        }
                        String licenseTitle = nameElement.getTextContent().trim();
                        if (licenseTitle.isEmpty()) {
                            continue;
                        }
                        String id = licenseTitle.toLowerCase(Locale.ROOT)
                                .replace(' ', '_')
                                .replace('.', '_');
                        result.setProperty("license." + id + ".name", licenseTitle);
                        copyChildText(license, "url", result, "license." + id + ".url");
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
                        BuildStep.linkOrCopy(target, file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && Files.exists(dir.resolve(BuildExecutor.SKIP_MARKER))) {
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
        private final MavenResolver resolver;
        private final transient MavenRepository repository;

        private Prepare(String prefix, MavenResolver resolver, MavenRepository repository) {
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
                MavenLocalPom value = entry.getValue();
                if (value.packaging() != null && !"jar".equals(value.packaging())) {
                    continue;
                }
                String coordinate = new MavenDependencyKey(value.groupId(), value.artifactId(), "jar", null)
                        .coordinate(prefix, value.version());
                MavenDependencyKey selfPom = new MavenDependencyKey(value.groupId(), value.artifactId(), "pom", null);
                String relativePath = entry.getKey().toString().replace(File.separatorChar, '/');
                String qualifiedDependencies = value.qualifiedDependencies() == null ? "" : value.qualifiedDependencies().entrySet().stream()
                        .map(requires -> requires.getKey() + "=" + requires.getValue())
                        .collect(Collectors.joining("\t"));
                writeModule(maven, value, relativePath, coordinate, selfPom, false, qualifiedDependencies);
                writeModule(maven, value, relativePath, coordinate, selfPom, true, qualifiedDependencies);
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        private void writeModule(Path maven,
                                 MavenLocalPom value,
                                 String relativePath,
                                 String coordinate,
                                 MavenDependencyKey selfPom,
                                 boolean test,
                                 String qualifiedDependencies) throws IOException {
            SequencedProperties properties = new SequencedProperties();
            properties.setProperty("coordinate", test
                    ? new MavenDependencyKey(value.groupId(), value.artifactId(), "jar", "tests")
                            .coordinate(prefix, value.version())
                    : coordinate);
            properties.setProperty("pom", selfPom.coordinate(prefix, value.version()));
            properties.setProperty("path", relativePath);
            properties.setProperty("groupId", value.groupId());
            properties.setProperty("artifactId", value.artifactId());
            properties.setProperty("version", value.version());
            if (value.release() != null) {
                properties.setProperty("release", value.release());
            }
            if (!test && value.mainClass() != null) {
                properties.setProperty("mainClass", value.mainClass());
            }
            if (test) {
                String testDependencies = value.dependencies() == null
                        ? ""
                        : value.dependencies().entrySet().stream()
                                .filter(dep -> dep.getValue().scope() == MavenDependencyScope.COMPILE
                                        || dep.getValue().scope() == MavenDependencyScope.RUNTIME
                                        || dep.getValue().scope() == MavenDependencyScope.PROVIDED
                                        || dep.getValue().scope() == MavenDependencyScope.TEST)
                                .map(dep -> dep.getKey().coordinate(prefix, dep.getValue().version()))
                                .collect(Collectors.joining(","));
                properties.setProperty("dependencies.test", testDependencies.isEmpty()
                        ? coordinate
                        : testDependencies + "," + coordinate);
            } else {
                for (MavenDependencyScope scope : List.of(
                        MavenDependencyScope.COMPILE,
                        MavenDependencyScope.PROVIDED,
                        MavenDependencyScope.RUNTIME)) {
                    properties.setProperty("dependencies." + scope.name().toLowerCase(Locale.ROOT),
                            value.dependencies() == null ? "" : value.dependencies().entrySet().stream()
                                    .filter(dep -> dep.getValue().scope() == scope)
                                    .map(dep -> dep.getKey().coordinate(prefix, dep.getValue().version()))
                                    .collect(Collectors.joining(",")));
                }
            }
            if (value.dependencies() != null) {
                value.dependencies().forEach((depKey, depValue) -> {
                    if (depValue.exclusions() != null && !depValue.exclusions().isEmpty()) {
                        properties.setProperty(
                                "exclusions." + depKey.coordinate(prefix, depValue.version()),
                                depValue.exclusions().stream()
                                        .map(name -> name.groupId() + "/" + name.artifactId())
                                        .collect(Collectors.joining(",")));
                    }
                });
            }
            String managed = value.managedDependencies() == null ? "" : value.managedDependencies().entrySet().stream()
                    .map(dep -> dep.getKey().coordinate(prefix, null)
                            + "=" + dep.getValue().version()
                            + (dep.getValue().checksum() == null ? "" : " " + dep.getValue().checksum()))
                    .collect(Collectors.joining(","));
            properties.setProperty("managedDependencies", managed);
            if (!qualifiedDependencies.isEmpty()) {
                properties.setProperty("qualifiedDependencies", qualifiedDependencies);
            }
            properties.setProperty("checksums",
                    value.dependencies() == null ? "" : value.dependencies().entrySet().stream()
                            .filter(dep -> dep.getValue().checksum() != null
                                    && (test
                                            ? dep.getValue().scope() == MavenDependencyScope.TEST
                                            : dep.getValue().scope() != MavenDependencyScope.TEST))
                            .map(dep -> dep.getKey().coordinate(prefix, dep.getValue().version())
                                    + "=" + dep.getValue().checksum())
                            .collect(Collectors.joining(",")));
            String sourceDirectory = test ? value.testSourceDirectory() : value.sourceDirectory();
            properties.setProperty("sources", sourceDirectory == null
                    ? (test ? "src/test/java" : "src/main/java")
                    : sourceDirectory.replace(File.separatorChar, '/'));
            List<String> resourceDirectories = test ? value.testResourceDirectories() : value.resourceDirectories();
            List<String> resources = resourceDirectories == null
                    ? List.of(test ? "src/test/resources" : "src/main/resources")
                    : resourceDirectories.stream()
                            .map(directory -> directory.replace(File.separatorChar, '/'))
                            .sorted()
                            .toList();
            for (int index = 0; index < resources.size(); index++) {
                properties.setProperty("resources." + index, resources.get(index));
            }
            properties.store(maven.resolve((test ? "test-module-" : "module-")
                    + BuildExecutorModule.encode(relativePath) + ".properties"));
        }
    }

    public record MavenModuleDescriptor(String name,
                                        SequencedSet<String> dependencies,
                                        SequencedSet<String> resources) implements ProjectModule {

        @Override
        public SequencedSet<String> sources() {
            return of(BuildExecutorModule.PREVIOUS + SOURCES);
        }

        @Override
        public SequencedSet<String> manifests() {
            return of(BuildExecutorModule.PREVIOUS + MANIFESTS);
        }

        @Override
        public SequencedSet<String> coordinates() {
            return of(BuildExecutorModule.PREVIOUS + COORDINATES);
        }

        @Override
        public SequencedSet<String> artifacts() {
            return of(BuildExecutorModule.PREVIOUS + DEPENDENCIES + "/" + Dependencies.ARTIFACTS);
        }

        private static SequencedSet<String> of(String value) {
            return Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(value)));
        }
    }
}
