package build.jenesis.maven;

import module java.base;
import module java.xml;
import build.jenesis.BuildStep;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Export;
import build.jenesis.step.FilePlacement;

public class MavenRepositoryPlacement implements FilePlacement {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC);

    public static BuildStep toLocalRepository() {
        return toRepository(Path.of(System.getProperty("user.home")).resolve(".m2").resolve("repository"));
    }

    public static BuildStep toRepository(Path target) {
        return new Export(target, new MavenRepositoryPlacement(), createMavenLocalMetadata());
    }

    @Override
    public Optional<Path> apply(Path file,
                                SequencedProperties module,
                                SequencedProperties metadata) throws IOException {
        boolean test = module.getProperty("tests") != null;
        String suffix = switch (file.getFileName().toString()) {
            case "classes.jar" -> test ? "-tests.jar" : ".jar";
            case "sources.jar" -> test ? "-tests-sources.jar" : "-sources.jar";
            case "javadoc.jar" -> test ? "-tests-javadoc.jar" : "-javadoc.jar";
            case "pom.xml" -> test ? null : ".pom";
            default -> null;
        };
        if (suffix == null) {
            return Optional.empty();
        }
        if (!Files.exists(file.getParent().resolve("pom.xml"))) {
            return Optional.empty();
        }
        String groupId = metadata.getProperty("project");
        String artifactId = metadata.getProperty("artifact");
        String version = metadata.getProperty("version");
        if (groupId == null || artifactId == null || version == null) {
            throw new IllegalStateException(
                    "Missing maven coordinates in metadata.properties for "
                            + file
                            + " (expected 'project', 'artifact' and 'version'; got project="
                            + groupId
                            + ", artifact="
                            + artifactId
                            + ", version="
                            + version
                            + ")");
        }
        return Optional.of(Path.of(
                groupId.replace('.', '/'),
                artifactId,
                version,
                artifactId + "-" + version + suffix));
    }

    @SuppressWarnings("unchecked")
    public static <C extends Consumer<Path> & Serializable> C createMavenLocalMetadata() {
        return (C) (Consumer<Path> & Serializable) (target -> {
            try {
                TransformerFactory transformerFactory = TransformerFactory.newInstance();
                String timestamp = TIMESTAMP.format(Instant.now());
                Map<Path, SequencedSet<String>> versionsByArtifact = new LinkedHashMap<>();
                Map<Path, PomCoordinates> sampleByArtifact = new HashMap<>();
                Map<Path, PomCoordinates> coordinatesByVersionDirectory = new HashMap<>();
                Files.walkFileTree(target, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".pom") || name.equals("pom.xml")) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path versionDirectory = file.getParent();
                        Path artifactDirectory = versionDirectory == null ? null : versionDirectory.getParent();
                        if (artifactDirectory == null) {
                            return FileVisitResult.CONTINUE;
                        }
                        Optional<PomCoordinates> coordinates = PomCoordinates.of(file);
                        if (coordinates.isEmpty()) {
                            return FileVisitResult.CONTINUE;
                        }
                        versionsByArtifact.computeIfAbsent(artifactDirectory, _ -> new LinkedHashSet<>())
                                .add(coordinates.get().version());
                        sampleByArtifact.putIfAbsent(artifactDirectory, coordinates.get());
                        coordinatesByVersionDirectory.put(versionDirectory, coordinates.get());
                        return FileVisitResult.CONTINUE;
                    }
                });
                for (Map.Entry<Path, SequencedSet<String>> entry : versionsByArtifact.entrySet()) {
                    Path artifactDirectory = entry.getKey();
                    PomCoordinates sample = sampleByArtifact.get(artifactDirectory);
                    List<String> sorted = entry.getValue().stream()
                            .sorted(MavenDefaultVersionNegotiator::compareVersions)
                            .toList();
                    String release = sorted.stream()
                            .filter(version -> !version.endsWith("-SNAPSHOT"))
                            .reduce((_, right) -> right)
                            .orElse(null);
                    Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                    Element metadata = (Element) document.appendChild(document.createElement("metadata"));
                    metadata.appendChild(document.createElement("groupId")).setTextContent(sample.groupId());
                    metadata.appendChild(document.createElement("artifactId")).setTextContent(sample.artifactId());
                    Element versioning = (Element) metadata.appendChild(document.createElement("versioning"));
                    if (release != null) {
                        versioning.appendChild(document.createElement("release")).setTextContent(release);
                    }
                    Element versionsNode = (Element) versioning.appendChild(document.createElement("versions"));
                    for (String version : sorted) {
                        versionsNode.appendChild(document.createElement("version")).setTextContent(version);
                    }
                    versioning.appendChild(document.createElement("lastUpdated")).setTextContent(timestamp);
                    try (OutputStream outputStream = Files.newOutputStream(artifactDirectory.resolve("maven-metadata-local.xml"))) {
                        Transformer transformer = transformerFactory.newTransformer();
                        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                        transformer.transform(new DOMSource(document), new StreamResult(outputStream));
                    }
                }
                for (Map.Entry<Path, PomCoordinates> entry : coordinatesByVersionDirectory.entrySet()) {
                    Path versionDirectory = entry.getKey();
                    PomCoordinates coordinates = entry.getValue();
                    if (coordinates.version().endsWith("-SNAPSHOT")) {
                        SequencedSet<String> snapshotKeys = new LinkedHashSet<>();
                        String prefix = coordinates.artifactId() + "-" + coordinates.version();
                        try (Stream<Path> stream = Files.list(versionDirectory)) {
                            stream.filter(Files::isRegularFile).forEach(file -> {
                                String name = file.getFileName().toString();
                                if (!name.startsWith(prefix)) {
                                    return;
                                }
                                int dotIndex = name.lastIndexOf('.');
                                if (dotIndex < 0) {
                                    return;
                                }
                                String extension = name.substring(dotIndex + 1);
                                if (extension.equals("xml") || extension.equals("repositories")) {
                                    return;
                                }
                                String middle = name.substring(prefix.length(), dotIndex);
                                String classifier = middle.startsWith("-") ? middle.substring(1) : "";
                                snapshotKeys.add(extension + "|" + classifier);
                            });
                        }
                        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                        Element metadata = (Element) document.appendChild(document.createElement("metadata"));
                        metadata.setAttribute("modelVersion", "1.1.0");
                        metadata.appendChild(document.createElement("groupId")).setTextContent(coordinates.groupId());
                        metadata.appendChild(document.createElement("artifactId")).setTextContent(coordinates.artifactId());
                        Element versioning = (Element) metadata.appendChild(document.createElement("versioning"));
                        versioning.appendChild(document.createElement("lastUpdated")).setTextContent(timestamp);
                        Element snapshot = (Element) versioning.appendChild(document.createElement("snapshot"));
                        snapshot.appendChild(document.createElement("localCopy")).setTextContent("true");
                        Element snapshotVersions = (Element) versioning.appendChild(document.createElement("snapshotVersions"));
                        for (String key : snapshotKeys) {
                            int separator = key.indexOf('|');
                            String extension = key.substring(0, separator);
                            String classifier = key.substring(separator + 1);
                            Element snapshotVersion = (Element) snapshotVersions.appendChild(document.createElement("snapshotVersion"));
                            if (!classifier.isEmpty()) {
                                snapshotVersion.appendChild(document.createElement("classifier")).setTextContent(classifier);
                            }
                            snapshotVersion.appendChild(document.createElement("extension")).setTextContent(extension);
                            snapshotVersion.appendChild(document.createElement("value")).setTextContent(coordinates.version());
                            snapshotVersion.appendChild(document.createElement("updated")).setTextContent(timestamp);
                        }
                        metadata.appendChild(document.createElement("version")).setTextContent(coordinates.version());
                        try (OutputStream outputStream = Files.newOutputStream(versionDirectory.resolve("maven-metadata-local.xml"))) {
                            Transformer transformer = transformerFactory.newTransformer();
                            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                            transformer.transform(new DOMSource(document), new StreamResult(outputStream));
                        }
                    }
                    StringBuilder body = new StringBuilder()
                            .append("#NOTE: This is a Maven Resolver internal implementation file, its format can be changed without prior notice.\n")
                            .append("#")
                            .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
                            .append('\n');
                    try (Stream<Path> files = Files.list(versionDirectory)) {
                        files.filter(Files::isRegularFile)
                                .map(file -> file.getFileName().toString())
                                .filter(name -> !name.endsWith(".xml") && !name.equals("_remote.repositories"))
                                .sorted()
                                .forEach(name -> body.append(name).append(">=\n"));
                    }
                    Files.writeString(versionDirectory.resolve("_remote.repositories"), body.toString());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (ParserConfigurationException | TransformerException e) {
                throw new UncheckedIOException(new IOException(e));
            }
        });
    }

    private record PomCoordinates(String groupId, String artifactId, String version) {

        private static Optional<PomCoordinates> of(Path pom) {
            String groupId = null, artifactId = null, version = null;
            try (InputStream stream = Files.newInputStream(pom)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                Element project = factory.newDocumentBuilder().parse(stream).getDocumentElement();
                NodeList children = project.getChildNodes();
                for (int index = 0; index < children.getLength(); index++) {
                    Node node = children.item(index);
                    if (node.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }
                    String name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
                    String text = node.getTextContent().trim();
                    switch (name) {
                        case "groupId" -> groupId = text;
                        case "artifactId" -> artifactId = text;
                        case "version" -> version = text;
                    }
                }
            } catch (IOException | ParserConfigurationException | SAXException _) {
                return Optional.empty();
            }
            if (groupId == null || artifactId == null || version == null) {
                return Optional.empty();
            }
            return Optional.of(new PomCoordinates(groupId, artifactId, version));
        }
    }
}
