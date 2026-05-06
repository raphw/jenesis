package build.jenesis.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

import module java.base;
import module java.xml;

public class Export implements BuildStep {

    private final Path target;
    private final Function<Path, Optional<Path>> placement;

    public <T extends Function<Path, Optional<Path>> & Serializable> Export(Path target, T placement) {
        this.target = target;
        this.placement = placement;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return true;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            Path folder = argument.folder();
            if (!Files.isDirectory(folder)) {
                continue;
            }
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Optional<Path> sub = placement.apply(file);
                    if (sub.isPresent()) {
                        Path destination = target.resolve(sub.get());
                        Path parent = destination.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    public static Export toLocalMavenRepository() {
        return toMavenRepository(Path.of(System.getProperty("user.home")).resolve(".m2").resolve("repository"));
    }

    public static Export toMavenRepository(Path target) {
        return new Export(target, mavenLayout());
    }

    @SuppressWarnings("unchecked")
    public static <T extends Function<Path, Optional<Path>> & Serializable> T mavenLayout() {
        return (T) (Function<Path, Optional<Path>> & Serializable) (file -> {
            Path filename = file.getFileName();
            if (filename == null) {
                return Optional.empty();
            }
            String suffix = switch (filename.toString()) {
                case "classes.jar" -> ".jar";
                case "pom.xml" -> ".pom";
                default -> null;
            };
            if (suffix == null) {
                return Optional.empty();
            }
            Path parent = file.getParent();
            if (parent == null) {
                return Optional.empty();
            }
            Path pom = parent.resolve("pom.xml");
            if (!Files.exists(pom)) {
                return Optional.empty();
            }
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
            } catch (IOException | ParserConfigurationException | SAXException e) {
                return Optional.empty();
            }
            if (groupId == null || artifactId == null || version == null) {
                return Optional.empty();
            }
            return Optional.of(Path.of(groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + suffix));
        });
    }
}
