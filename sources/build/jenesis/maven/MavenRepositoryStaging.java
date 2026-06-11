package build.jenesis.maven;

import module java.base;
import module java.xml;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;

public class MavenRepositoryStaging implements BuildStep {

    private final boolean includeTests;

    public MavenRepositoryStaging() {
        this(Boolean.getBoolean("jenesis.stage.tests"));
    }

    public MavenRepositoryStaging(boolean includeTests) {
        this.includeTests = includeTests;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Collected collected = collectModules(arguments);
        Pairings pairings = pairTests(collected.mainsByArtifactId(), collected.testModules());
        stageModules(context.next(), collected.mainsByArtifactId(), pairings);
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private Collected collectModules(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        SequencedMap<String, Module> mainsByArtifactId = new LinkedHashMap<>();
        List<Module> testModules = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path inventoryFile = argument.folder().resolve(Inventory.INVENTORY);
            if (!Files.isRegularFile(inventoryFile)) {
                continue;
            }
            SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
            String prefix = inventoryPrefix(inventory, inventoryFile);
            Path pom = resolve(argument.folder(), inventory.getProperty(prefix + ".pom"));
            if (pom == null) {
                continue;
            }
            Coordinates coordinates = parseCoordinates(pom);
            Path artifact = singleJar(Inventory.paths(inventory, argument.folder(), prefix + ".artifacts"),
                    prefix, "artifacts", true, inventoryFile);
            Path sources = singleJar(Inventory.paths(inventory, argument.folder(), prefix + ".sources"),
                    prefix, "sources", false, inventoryFile);
            Path javadoc = singleJar(Inventory.paths(inventory, argument.folder(), prefix + ".documentation"),
                    prefix, "documentation", false, inventoryFile);
            Path sbom = sbomReport(inventory, argument.folder(), prefix);
            String testsOf = inventory.getProperty(prefix + ".test");
            Module module = new Module(prefix, coordinates, artifact, sources, javadoc, pom, testsOf, sbom);
            if (testsOf == null) {
                Module previous = mainsByArtifactId.putIfAbsent(coordinates.artifactId(), module);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate main artifactId '"
                            + coordinates.artifactId()
                            + "' declared by inventories '"
                            + previous.prefix()
                            + "' ("
                            + previous.coordinates().groupId()
                            + ":"
                            + previous.coordinates().artifactId()
                            + ":"
                            + previous.coordinates().version()
                            + ") and '"
                            + prefix
                            + "' ("
                            + coordinates.groupId()
                            + ":"
                            + coordinates.artifactId()
                            + ":"
                            + coordinates.version()
                            + ")");
                }
            } else if (includeTests) {
                testModules.add(module);
            }
        }
        return new Collected(mainsByArtifactId, testModules);
    }

    private static Pairings pairTests(SequencedMap<String, Module> mainsByArtifactId,
                                      List<Module> testModules) throws IOException {
        SequencedMap<String, Module> testByMain = new LinkedHashMap<>();
        SequencedMap<String, List<DependencyEntry>> testDepsByMain = new LinkedHashMap<>();
        Set<String> allMainArtifactIds = mainsByArtifactId.keySet();
        for (Module test : testModules) {
            Module main;
            if (test.testsOf().isEmpty()) {
                if (mainsByArtifactId.isEmpty()) {
                    throw new IllegalStateException("Test module '"
                            + test.prefix()
                            + "' does not name the main module it tests (bare @jenesis.test) "
                            + "but no main module is present to attach it to");
                }
                if (mainsByArtifactId.size() > 1) {
                    throw new IllegalStateException("Test module '"
                            + test.prefix()
                            + "' does not name the main module it tests (bare @jenesis.test) "
                            + "but multiple main modules are present; "
                            + "specify an explicit @jenesis.test <artifactId> (known mains: "
                            + mainsByArtifactId.keySet()
                            + ")");
                }
                main = mainsByArtifactId.values().iterator().next();
            } else {
                main = mainsByArtifactId.get(test.testsOf());
                if (main == null) {
                    throw new IllegalStateException("Test module '"
                            + test.prefix()
                            + "' references unknown main '"
                            + test.testsOf()
                            + "' (known mains: "
                            + mainsByArtifactId.keySet()
                            + ")");
                }
            }
            Module previous = testByMain.putIfAbsent(main.coordinates().artifactId(), test);
            if (previous != null) {
                throw new IllegalStateException("Multiple test modules name main '"
                        + main.coordinates().artifactId()
                        + "' as the module they test (would collide on the '-tests' classifier): "
                        + List.of(previous.prefix(), test.prefix()));
            }
            if (test.pom() != null) {
                collectDependencies(test.pom(),
                        allMainArtifactIds,
                        testDepsByMain.computeIfAbsent(main.coordinates().artifactId(), _ -> new ArrayList<>()));
            }
        }
        return new Pairings(testByMain, testDepsByMain);
    }

    private static void stageModules(Path target,
                                     SequencedMap<String, Module> mainsByArtifactId,
                                     Pairings pairings) throws IOException {
        for (Module main : mainsByArtifactId.values()) {
            Coordinates coordinates = main.coordinates();
            Path baseDir = target
                    .resolve(coordinates.groupId().replace('.', '/'))
                    .resolve(coordinates.artifactId())
                    .resolve(coordinates.version());
            Files.createDirectories(baseDir);
            String prefix = coordinates.artifactId() + "-" + coordinates.version();
            link(main.artifact(), baseDir.resolve(prefix + ".jar"));
            link(main.sources(), baseDir.resolve(prefix + "-sources.jar"));
            link(main.javadoc(), baseDir.resolve(prefix + "-javadoc.jar"));
            Path stagedPom = baseDir.resolve(prefix + ".pom");
            if (!Files.exists(stagedPom)) {
                List<DependencyEntry> deps = pairings.testDepsByMain().getOrDefault(coordinates.artifactId(), List.of());
                if (deps.isEmpty()) {
                    BuildStep.linkOrCopy(stagedPom, main.pom());
                } else {
                    writeMergedPom(main.pom(), deps, stagedPom);
                }
            }
            if (main.sbom() != null) {
                String name = main.sbom().getFileName().toString();
                int dot = name.lastIndexOf('.');
                link(main.sbom(), baseDir.resolve(prefix + "-cyclonedx" + (dot < 0 ? "" : name.substring(dot))));
            }
            Module test = pairings.testByMain().get(coordinates.artifactId());
            if (test != null) {
                link(test.artifact(), baseDir.resolve(prefix + "-tests.jar"));
                link(test.sources(), baseDir.resolve(prefix + "-tests-sources.jar"));
                link(test.javadoc(), baseDir.resolve(prefix + "-tests-javadoc.jar"));
            }
        }
    }

    private record Collected(SequencedMap<String, Module> mainsByArtifactId, List<Module> testModules) {
    }

    private record Pairings(SequencedMap<String, Module> testByMain,
                            SequencedMap<String, List<DependencyEntry>> testDepsByMain) {
    }

    private record Module(String prefix,
                          Coordinates coordinates,
                          Path artifact,
                          Path sources,
                          Path javadoc,
                          Path pom,
                          String testsOf,
                          Path sbom) {
    }

    private record Coordinates(String groupId, String artifactId, String version) {
    }

    private record DependencyEntry(String groupId, String artifactId, String version) {
    }

    private static String inventoryPrefix(SequencedProperties inventory, Path file) {
        for (String key : inventory.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                return key.substring(0, dot);
            }
        }
        throw new IllegalStateException("Inventory contains no prefixed keys: " + file);
    }

    private static Path resolve(Path base, String relative) {
        if (relative == null) {
            return null;
        }
        Path resolved = base.resolve(relative).normalize();
        return Files.isRegularFile(resolved) ? resolved : null;
    }

    private static Path sbomReport(SequencedProperties inventory, Path base, String prefix) throws IOException {
        String value = inventory.getProperty(prefix + ".report.sbom");
        if (value == null) {
            return null;
        }
        Path folder = base.resolve(value).normalize();
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    return file;
                }
            }
        }
        return null;
    }

    private static Path singleJar(List<Path> entries,
                                  String prefix,
                                  String kind,
                                  boolean required,
                                  Path inventoryFile) {
        if (entries.isEmpty()) {
            if (required) {
                throw new IllegalStateException("Missing '"
                        + prefix
                        + "."
                        + kind
                        + "' in inventory: "
                        + inventoryFile);
            }
            return null;
        }
        List<Path> jars = new ArrayList<>();
        for (Path candidate : entries) {
            if (candidate.getFileName().toString().endsWith(".jar") && Files.isRegularFile(candidate)) {
                jars.add(candidate);
            }
        }
        if (jars.isEmpty()) {
            if (required) {
                throw new IllegalStateException("No '.jar' file listed in '"
                        + prefix
                        + "."
                        + kind
                        + "' ("
                        + entries
                        + ") in inventory: "
                        + inventoryFile);
            }
            return null;
        }
        if (jars.size() > 1) {
            throw new IllegalStateException((required ? "Expected exactly one '.jar' in '" : "Expected at most one '.jar' in '")
                    + prefix
                    + "."
                    + kind
                    + "', got "
                    + jars.size()
                    + " ("
                    + jars
                    + ") in inventory: "
                    + inventoryFile);
        }
        return jars.getFirst();
    }

    private static void link(Path source, Path target) throws IOException {
        if (source != null && !Files.exists(target)) {
            BuildStep.linkOrCopy(target, source);
        }
    }

    private static Coordinates parseCoordinates(Path pom) throws IOException {
        Document document;
        try (InputStream in = Files.newInputStream(pom)) {
            document = parse(in);
        }
        Element project = document.getDocumentElement();
        String groupId = childText(project, "groupId");
        String artifactId = childText(project, "artifactId");
        String version = childText(project, "version");
        if (groupId == null || artifactId == null || version == null) {
            throw new IllegalStateException("Missing maven coordinates in pom "
                    + pom
                    + " (groupId="
                    + groupId
                    + ", artifactId="
                    + artifactId
                    + ", version="
                    + version
                    + ")");
        }
        return new Coordinates(groupId, artifactId, version);
    }

    private static void collectDependencies(Path pom,
                                            Set<String> excludeArtifactIds,
                                            List<DependencyEntry> sink) throws IOException {
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
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
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
