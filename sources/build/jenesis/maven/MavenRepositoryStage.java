package build.jenesis.maven;

import module java.base;
import module java.xml;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

public class MavenRepositoryStage implements BuildStep {

    private static final String POM = "pom.xml";
    private static final String METADATA = "metadata.properties";

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Map<String, Path> mainModules = new LinkedHashMap<>();
        Map<String, TestModule> testModules = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(argument.folder())) {
                for (Path moduleDir : stream) {
                    if (!Files.isDirectory(moduleDir)) {
                        continue;
                    }
                    Path metadata = moduleDir.resolve(METADATA);
                    if (!Files.isRegularFile(metadata)) {
                        continue;
                    }
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(metadata)) {
                        properties.load(reader);
                    }
                    String testOf = properties.getProperty("project.test");
                    if (testOf != null) {
                        testModules.put(moduleDir.getFileName().toString(), new TestModule(moduleDir, testOf));
                    } else {
                        mainModules.put(moduleDir.getFileName().toString(), moduleDir);
                    }
                }
            }
        }
        Map<String, Coordinates> mainsByArtifactId = new LinkedHashMap<>();
        Map<String, Coordinates> mainsByDir = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : mainModules.entrySet()) {
            Coordinates coordinates = readCoordinates(entry.getValue().resolve(POM));
            if (coordinates != null) {
                mainsByDir.put(entry.getKey(), coordinates);
                mainsByArtifactId.put(coordinates.artifactId(), coordinates);
            }
        }
        Map<String, List<DependencyEntry>> testDepsByMain = new LinkedHashMap<>();
        Map<String, List<TestModule>> testsByMain = new LinkedHashMap<>();
        Coordinates fallbackMain = mainsByArtifactId.values().stream().findFirst().orElse(null);
        Set<String> allMainArtifactIds = mainsByArtifactId.keySet();
        for (Map.Entry<String, TestModule> entry : testModules.entrySet()) {
            TestModule test = entry.getValue();
            Coordinates parent;
            if (test.testOf().isEmpty()) {
                if (fallbackMain == null) {
                    throw new IllegalStateException("Test module '"
                            + entry.getKey()
                            + "' declares no parent (bare @test) but no main module is present to attach it to");
                }
                parent = fallbackMain;
            } else {
                parent = mainsByArtifactId.get(test.testOf());
                if (parent == null) {
                    throw new IllegalStateException("Test module '"
                            + entry.getKey()
                            + "' references unknown main '"
                            + test.testOf()
                            + "' (known mains: "
                            + mainsByArtifactId.keySet()
                            + ")");
                }
            }
            testsByMain.computeIfAbsent(parent.artifactId(), _ -> new ArrayList<>()).add(test);
            collectDependencies(test.dir().resolve(POM),
                    allMainArtifactIds,
                    testDepsByMain.computeIfAbsent(parent.artifactId(), _ -> new ArrayList<>()));
        }
        for (Map.Entry<String, List<TestModule>> entry : testsByMain.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<String> dirs = entry.getValue().stream()
                        .map(test -> test.dir().getFileName().toString())
                        .toList();
                throw new IllegalStateException("Multiple test modules declare main '"
                        + entry.getKey()
                        + "' as their parent (would collide on the '-test' classifier): "
                        + dirs);
            }
        }
        for (Map.Entry<String, Path> entry : mainModules.entrySet()) {
            Coordinates coordinates = mainsByDir.get(entry.getKey());
            if (coordinates == null) {
                continue;
            }
            List<DependencyEntry> deps = testDepsByMain.getOrDefault(coordinates.artifactId(), List.of());
            stageMainModule(entry.getValue(), coordinates, deps, context.next());
            for (TestModule test : testsByMain.getOrDefault(coordinates.artifactId(), List.of())) {
                stageTestModule(test.dir(), coordinates, context.next());
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private record TestModule(Path dir, String testOf) {
    }

    private static void stageMainModule(Path moduleDir,
                                        Coordinates coordinates,
                                        List<DependencyEntry> testDeps,
                                        Path stagingRoot) throws IOException {
        Path baseDir = baseDirFor(stagingRoot, coordinates);
        Files.createDirectories(baseDir);
        String prefix = coordinates.artifactId() + "-" + coordinates.version();
        link(moduleDir.resolve("classes.jar"), baseDir.resolve(prefix + ".jar"));
        link(moduleDir.resolve("sources.jar"), baseDir.resolve(prefix + "-sources.jar"));
        link(moduleDir.resolve("javadoc.jar"), baseDir.resolve(prefix + "-javadoc.jar"));
        Path stagedPom = baseDir.resolve(prefix + ".pom");
        Path sourcePom = moduleDir.resolve(POM);
        if (Files.isRegularFile(sourcePom) && !Files.exists(stagedPom)) {
            if (testDeps.isEmpty()) {
                Files.createLink(stagedPom, sourcePom);
            } else {
                writeMergedPom(sourcePom, testDeps, stagedPom);
            }
        }
    }

    private static void stageTestModule(Path moduleDir,
                                        Coordinates coordinates,
                                        Path stagingRoot) throws IOException {
        Path baseDir = baseDirFor(stagingRoot, coordinates);
        Files.createDirectories(baseDir);
        String prefix = coordinates.artifactId() + "-" + coordinates.version();
        link(moduleDir.resolve("classes.jar"), baseDir.resolve(prefix + "-test.jar"));
        link(moduleDir.resolve("sources.jar"), baseDir.resolve(prefix + "-test-sources.jar"));
        link(moduleDir.resolve("javadoc.jar"), baseDir.resolve(prefix + "-test-javadoc.jar"));
    }

    private static Path baseDirFor(Path stagingRoot, Coordinates coordinates) {
        return stagingRoot
                .resolve(coordinates.groupId().replace('.', '/'))
                .resolve(coordinates.artifactId())
                .resolve(coordinates.version());
    }

    private static void link(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source) && !Files.exists(target)) {
            Files.createLink(target, source);
        }
    }

    private static Coordinates readCoordinates(Path pom) throws IOException {
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(pom)) {
            Element project = parse(in).getDocumentElement();
            String groupId = childText(project, "groupId");
            String artifactId = childText(project, "artifactId");
            String version = childText(project, "version");
            if (groupId == null || artifactId == null || version == null) {
                return null;
            }
            return new Coordinates(groupId, artifactId, version);
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
        for (DependencyEntry entry : testDeps) {
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

    private record Coordinates(String groupId, String artifactId, String version) {
    }

    private record DependencyEntry(String groupId, String artifactId, String version) {
    }
}
