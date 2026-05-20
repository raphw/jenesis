package build.jenesis.maven;

import module java.base;
import module java.xml;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class MavenRepositoryStaging implements BuildStep {

    private static final String POM = "pom.xml";

    private final boolean includeTests;

    public MavenRepositoryStaging() {
        this(false);
    }

    public MavenRepositoryStaging(boolean includeTests) {
        this.includeTests = includeTests;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, Module> mainsByDir = new LinkedHashMap<>();
        SequencedMap<String, TestModule> testsByDir = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(argument.folder())) {
                for (Path moduleDir : stream) {
                    if (!Files.isDirectory(moduleDir)) {
                        continue;
                    }
                    Path metadataFile = moduleDir.resolve(BuildStep.METADATA);
                    if (!Files.isRegularFile(metadataFile)) {
                        continue;
                    }
                    SequencedProperties metadata = SequencedProperties.ofFiles(metadataFile);
                    String groupId = metadata.getProperty("project");
                    String artifactId = metadata.getProperty("artifact");
                    String version = metadata.getProperty("version");
                    if (groupId == null || artifactId == null || version == null) {
                        throw new IllegalStateException(
                                "Missing maven coordinates in metadata.properties for "
                                        + moduleDir
                                        + " (expected 'project', 'artifact' and 'version'; got project="
                                        + groupId
                                        + ", artifact="
                                        + artifactId
                                        + ", version="
                                        + version
                                        + ")");
                    }
                    Coordinates coordinates = new Coordinates(groupId, artifactId, version);
                    Path moduleFile = moduleDir.resolve(BuildStep.MODULE);
                    String testOf = Files.isRegularFile(moduleFile)
                            ? SequencedProperties.ofFiles(moduleFile).getProperty("tests")
                            : null;
                    if (testOf != null) {
                        if (includeTests) {
                            testsByDir.put(moduleDir.getFileName().toString(),
                                    new TestModule(moduleDir, coordinates, testOf));
                        }
                    } else {
                        mainsByDir.put(moduleDir.getFileName().toString(),
                                new Module(moduleDir, coordinates));
                    }
                }
            }
        }
        SequencedMap<String, Module> mainsByArtifactId = new LinkedHashMap<>();
        for (Map.Entry<String, Module> entry : mainsByDir.entrySet()) {
            Module module = entry.getValue();
            Module previous = mainsByArtifactId.putIfAbsent(module.coordinates().artifactId(), module);
            if (previous != null) {
                throw new IllegalStateException("Duplicate main artifactId '"
                        + module.coordinates().artifactId()
                        + "' declared by modules '"
                        + previous.dir().getFileName()
                        + "' ("
                        + previous.coordinates().groupId()
                        + ":"
                        + previous.coordinates().artifactId()
                        + ":"
                        + previous.coordinates().version()
                        + ") and '"
                        + entry.getKey()
                        + "' ("
                        + module.coordinates().groupId()
                        + ":"
                        + module.coordinates().artifactId()
                        + ":"
                        + module.coordinates().version()
                        + ")");
            }
        }
        SequencedMap<String, List<TestModule>> testsByMain = new LinkedHashMap<>();
        SequencedMap<String, List<DependencyEntry>> testDepsByMain = new LinkedHashMap<>();
        Set<String> allMainArtifactIds = mainsByArtifactId.keySet();
        for (Map.Entry<String, TestModule> entry : testsByDir.entrySet()) {
            TestModule test = entry.getValue();
            Module main;
            if (test.testOf().isEmpty()) {
                if (mainsByArtifactId.isEmpty()) {
                    throw new IllegalStateException("Test module '"
                            + entry.getKey()
                            + "' does not name the main module it tests (bare @tests) "
                            + "but no main module is present to attach it to");
                }
                if (mainsByArtifactId.size() > 1) {
                    throw new IllegalStateException("Test module '"
                            + entry.getKey()
                            + "' does not name the main module it tests (bare @tests) "
                            + "but multiple main modules are present; "
                            + "specify an explicit @tests <artifactId> (known mains: "
                            + mainsByArtifactId.keySet()
                            + ")");
                }
                main = mainsByArtifactId.values().iterator().next();
            } else {
                main = mainsByArtifactId.get(test.testOf());
                if (main == null) {
                    throw new IllegalStateException("Test module '"
                            + entry.getKey()
                            + "' references unknown main '"
                            + test.testOf()
                            + "' (known mains: "
                            + mainsByArtifactId.keySet()
                            + ")");
                }
            }
            testsByMain.computeIfAbsent(main.coordinates().artifactId(), _ -> new ArrayList<>()).add(test);
            collectDependencies(test.dir().resolve(POM),
                    allMainArtifactIds,
                    testDepsByMain.computeIfAbsent(main.coordinates().artifactId(), _ -> new ArrayList<>()));
        }
        for (Map.Entry<String, List<TestModule>> entry : testsByMain.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<String> dirs = entry.getValue().stream()
                        .map(test -> test.dir().getFileName().toString())
                        .toList();
                throw new IllegalStateException("Multiple test modules name main '"
                        + entry.getKey()
                        + "' as the module they test (would collide on the '-tests' classifier): "
                        + dirs);
            }
        }
        for (Module main : mainsByDir.values()) {
            Coordinates coordinates = main.coordinates();
            Path baseDir = context.next()
                    .resolve(coordinates.groupId().replace('.', '/'))
                    .resolve(coordinates.artifactId())
                    .resolve(coordinates.version());
            Files.createDirectories(baseDir);
            String prefix = coordinates.artifactId() + "-" + coordinates.version();
            Path mainDir = main.dir();
            link(mainDir.resolve("classes.jar"), baseDir.resolve(prefix + ".jar"));
            link(mainDir.resolve("sources.jar"), baseDir.resolve(prefix + "-sources.jar"));
            link(mainDir.resolve("javadoc.jar"), baseDir.resolve(prefix + "-javadoc.jar"));
            Path sourcePom = mainDir.resolve(POM);
            Path stagedPom = baseDir.resolve(prefix + ".pom");
            if (Files.isRegularFile(sourcePom) && !Files.exists(stagedPom)) {
                List<DependencyEntry> deps = testDepsByMain.getOrDefault(coordinates.artifactId(), List.of());
                if (deps.isEmpty()) {
                    Files.createLink(stagedPom, sourcePom);
                } else {
                    writeMergedPom(sourcePom, deps, stagedPom);
                }
            }
            for (TestModule test : testsByMain.getOrDefault(coordinates.artifactId(), List.of())) {
                link(test.dir().resolve("classes.jar"), baseDir.resolve(prefix + "-tests.jar"));
                link(test.dir().resolve("sources.jar"), baseDir.resolve(prefix + "-tests-sources.jar"));
                link(test.dir().resolve("javadoc.jar"), baseDir.resolve(prefix + "-tests-javadoc.jar"));
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private record Module(Path dir, Coordinates coordinates) {
    }

    private record TestModule(Path dir, Coordinates coordinates, String testOf) {
    }

    private record Coordinates(String groupId, String artifactId, String version) {
    }

    private record DependencyEntry(String groupId, String artifactId, String version) {
    }

    private static void link(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source) && !Files.exists(target)) {
            Files.createLink(target, source);
        }
    }

    private static void collectDependencies(Path pom,
                                            Set<String> excludeArtifactIds,
                                            List<DependencyEntry> sink) throws IOException {
        if (!Files.isRegularFile(pom)) {
            return;
        }
        try (InputStream in = Files.newInputStream(pom)) {
            NodeList depNodes = parse(in).getElementsByTagNameNS("*", "dependency");
            for (int index = 0; index < depNodes.getLength(); index++) {
                Element dep = (Element) depNodes.item(index);
                String artifactId = childText(dep, "artifactId");
                if (artifactId == null || excludeArtifactIds.contains(artifactId)) {
                    continue;
                }
                sink.add(new DependencyEntry(
                        childText(dep, "groupId"),
                        artifactId,
                        childText(dep, "version")));
            }
        }
    }

    private static void writeMergedPom(Path source,
                                       List<DependencyEntry> testDeps,
                                       Path target) throws IOException {
        Document document;
        try (InputStream in = Files.newInputStream(source)) {
            document = parse(in);
        }
        Element project = document.getDocumentElement();
        String namespace = project.getNamespaceURI();
        Element dependencies = findChild(project, "dependencies");
        if (dependencies == null) {
            dependencies = namespace == null
                    ? document.createElement("dependencies")
                    : document.createElementNS(namespace, "dependencies");
            project.appendChild(dependencies);
        }
        Set<String> existing = new HashSet<>();
        NodeList existingDeps = dependencies.getChildNodes();
        for (int index = 0; index < existingDeps.getLength(); index++) {
            Node node = existingDeps.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element dep = (Element) node;
            existing.add(childText(dep, "groupId") + ":" + childText(dep, "artifactId"));
        }
        for (DependencyEntry entry : testDeps) {
            if (!existing.add(entry.groupId() + ":" + entry.artifactId())) {
                continue;
            }
            Element dependency = namespace == null
                    ? document.createElement("dependency")
                    : document.createElementNS(namespace, "dependency");
            appendChild(document, dependency, namespace, "groupId", entry.groupId());
            appendChild(document, dependency, namespace, "artifactId", entry.artifactId());
            appendChild(document, dependency, namespace, "version", entry.version());
            appendChild(document, dependency, namespace, "scope", "test");
            dependencies.appendChild(dependency);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.transform(new DOMSource(document), new StreamResult(out));
        } catch (TransformerException e) {
            throw new IOException(e);
        }
    }

    private static Document parse(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(e);
        }
    }

    private static Element findChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
            if (localName.equals(name)) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName) {
        Element child = findChild(parent, localName);
        return child == null ? null : child.getTextContent().trim();
    }

    private static void appendChild(Document document,
                                    Element parent,
                                    String namespace,
                                    String localName,
                                    String value) {
        if (value == null) {
            return;
        }
        Element child = namespace == null
                ? document.createElement(localName)
                : document.createElementNS(namespace, localName);
        child.setTextContent(value);
        parent.appendChild(child);
    }
}
