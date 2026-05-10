package build.jenesis.maven;

import module java.base;
import module java.xml;

import static build.jenesis.maven.MavenPomResolver.missing;
import static build.jenesis.maven.MavenPomResolver.toChildren;

public class MavenDefaultVersionNegotiator implements MavenVersionNegotiator {

    private final transient DocumentBuilderFactory documentBuilderFactory;
    private final Map<MavenDependencyName, Metadata> cache = new HashMap<>();

    private MavenDefaultVersionNegotiator(DocumentBuilderFactory documentBuilderFactory) {
        this.documentBuilderFactory = documentBuilderFactory;
    }

    static DocumentBuilderFactory toDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return factory;
    }

    @SuppressWarnings("unchecked")
    public static <S extends Supplier<MavenVersionNegotiator> & Serializable> S maven() {
        return (S) (Supplier<MavenVersionNegotiator> & Serializable) () -> new MavenDefaultVersionNegotiator(toDocumentBuilderFactory()) {
            @Override
            public String resolve(Executor executor,
                                  MavenRepository repository,
                                  String groupId,
                                  String artifactId,
                                  String type,
                                  String classifier,
                                  String current,
                                  SequencedSet<String> versions) throws IOException {
                List<List<Restriction>> hardRequirements = new ArrayList<>();
                for (String version : versions) {
                    if (isRange(version)) {
                        hardRequirements.add(parseRanges(version));
                    }
                }
                if (hardRequirements.isEmpty()) {
                    return current;
                }
                return toMetadata(executor, repository, groupId, artifactId).versions().stream()
                        .filter(candidate -> hardRequirements.stream().allMatch(restrictions ->
                                restrictions.stream().anyMatch(restriction -> restriction.contains(candidate))))
                        .reduce((_, right) -> right)
                        .orElseThrow(() -> new IllegalStateException(
                                "Could not resolve version conflict for " + groupId + ":" + artifactId
                                        + " among " + versions));
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <S extends Supplier<MavenVersionNegotiator> & Serializable> S latest() {
        return (S) (Supplier<MavenVersionNegotiator> & Serializable) () -> new MavenDefaultVersionNegotiator(toDocumentBuilderFactory()) {
            @Override
            public String resolve(Executor executor,
                                  MavenRepository repository,
                                  String groupId,
                                  String artifactId,
                                  String type,
                                  String classifier,
                                  String version) throws IOException {
                return toMetadata(executor, repository, groupId, artifactId).latest();
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <S extends Supplier<MavenVersionNegotiator> & Serializable> S release() {
        return (S) (Supplier<MavenVersionNegotiator> & Serializable) () -> new MavenDefaultVersionNegotiator(toDocumentBuilderFactory()) {
            @Override
            public String resolve(Executor executor,
                                  MavenRepository repository,
                                  String groupId,
                                  String artifactId,
                                  String type,
                                  String classifier,
                                  String version) throws IOException {
                return toMetadata(executor, repository, groupId, artifactId).release();
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <S extends Supplier<MavenVersionNegotiator> & Serializable> S closest() {
        return (S) (Supplier<MavenVersionNegotiator> & Serializable) () -> new MavenDefaultVersionNegotiator(toDocumentBuilderFactory());
    }

    @Override
    public String resolve(Executor executor,
                          MavenRepository repository,
                          String groupId,
                          String artifactId,
                          String type,
                          String classifier,
                          String version) throws IOException {
        return switch (version) {
            case "RELEASE" -> toMetadata(executor, repository, groupId, artifactId).release();
            case "LATEST" -> toMetadata(executor, repository, groupId, artifactId).latest();
            case String range when isRange(range) -> {
                List<Restriction> restrictions = parseRanges(range);
                yield toMetadata(executor, repository, groupId, artifactId).versions().stream()
                        .filter(candidate -> restrictions.stream().anyMatch(r -> r.contains(candidate)))
                        .reduce((_, right) -> right)
                        .orElseThrow(() -> new IllegalStateException("Could not resolve version in range: " + version));
            }
            default -> version;
        };
    }

    static boolean isRange(String version) {
        return !version.isEmpty()
                && (version.charAt(0) == '[' || version.charAt(0) == '(')
                && (version.charAt(version.length() - 1) == ']' || version.charAt(version.length() - 1) == ')');
    }

    static List<Restriction> parseRanges(String input) {
        List<Restriction> result = new ArrayList<>();
        int index = 0;
        while (index < input.length()) {
            while (index < input.length() && (input.charAt(index) == ',' || Character.isWhitespace(input.charAt(index)))) {
                index++;
            }
            if (index >= input.length()) {
                break;
            }
            char open = input.charAt(index);
            if (open != '[' && open != '(') {
                throw new IllegalArgumentException("Invalid version range: " + input);
            }
            int close = index + 1;
            while (close < input.length() && input.charAt(close) != ']' && input.charAt(close) != ')') {
                close++;
            }
            if (close >= input.length()) {
                throw new IllegalArgumentException("Unclosed version range: " + input);
            }
            String value = input.substring(index + 1, close).trim();
            char closeChar = input.charAt(close);
            String lower, upper;
            int comma = value.indexOf(',');
            if (comma == -1) {
                lower = upper = value;
            } else {
                lower = value.substring(0, comma).trim();
                upper = value.substring(comma + 1).trim();
            }
            result.add(new Restriction(
                    lower.isEmpty() ? null : lower,
                    open == '[',
                    upper.isEmpty() ? null : upper,
                    closeChar == ']'));
            index = close + 1;
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Invalid version range: " + input);
        }
        return result;
    }

    record Restriction(String lower,
                       boolean lowerInclusive,
                       String upper,
                       boolean upperInclusive) {
        boolean contains(String version) {
            if (lower != null) {
                int c = compare(lower, version);
                if (lowerInclusive ? c > 0 : c >= 0) {
                    return false;
                }
            }
            if (upper != null) {
                int c = compare(version, upper);
                if (upperInclusive ? c > 0 : c >= 0) {
                    return false;
                }
            }
            return true;
        }
    }

    Metadata toMetadata(Executor executor,
                        MavenRepository repository,
                        String groupId,
                        String artifactId) throws IOException {
        Metadata metadata = cache.get(new MavenDependencyName(groupId, artifactId));
        if (metadata == null) {
            Document document;
            try (InputStream inputStream = repository.fetchMetadata(executor, groupId, artifactId, null)
                    .orElseThrow(() -> new IllegalStateException("No metadata for " + groupId + ":" + artifactId))
                    .toInputStream()) {
                document = documentBuilderFactory.newDocumentBuilder().parse(inputStream);
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
            metadata = switch (document.getDocumentElement().getAttribute("modelVersion")) {
                case "1.1.0" -> {
                    Node versioning = toChildren(document.getDocumentElement())
                            .filter(node -> Objects.equals(node.getLocalName(), "versioning"))
                            .findFirst()
                            .orElseThrow(missing("versioning"));
                    yield new Metadata(
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "latest"))
                                    .findFirst()
                                    .map(Node::getTextContent)
                                    .orElseThrow(missing("latest")),
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "release"))
                                    .findFirst()
                                    .map(Node::getTextContent)
                                    .orElseThrow(missing("release")),
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "versions"))
                                    .findFirst()
                                    .stream()
                                    .flatMap(MavenPomResolver::toChildren)
                                    .filter(node -> Objects.equals(node.getLocalName(), "version"))
                                    .map(Node::getTextContent)
                                    .toList());
                }
                case null, default -> throw new IllegalStateException("Unknown model version: " +
                        document.getDocumentElement().getAttribute("modelVersion"));
            };
            cache.put(new MavenDependencyName(groupId, artifactId), metadata);
        }
        return metadata;
    }

    static int compare(String left, String right) {
        int leftIndex = 0, rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            if (left.charAt(leftIndex) == '.' || left.charAt(leftIndex) == '-') {
                leftIndex++;
            }
            if (rightIndex < right.length() && (right.charAt(rightIndex) == '.' || right.charAt(rightIndex) == '-')) {
                rightIndex++;
            }
            if (leftIndex >= left.length() || rightIndex >= right.length()) {
                break;
            }
            int leftNext = leftIndex, rightNext = rightIndex;
            while (leftNext < left.length()
                    && left.charAt(leftNext) != '.'
                    && left.charAt(leftNext) != '-') {
                leftNext++;
            }
            while (rightNext < right.length()
                    && right.charAt(rightNext) != '.'
                    && right.charAt(rightNext) != '-') {
                rightNext++;
            }
            boolean leftText = false, rightText = false;
            int leftInteger = 0, rightInteger = 0;
            try {
                leftInteger = Integer.parseInt(left, leftIndex, leftNext, 10);
            } catch (NumberFormatException _) {
                leftText = true;
            }
            try {
                rightInteger = Integer.parseInt(right, rightIndex, rightNext, 10);
            } catch (NumberFormatException _) {
                rightText = true;
            }
            int comparison;
            if (leftText && rightText) {
                comparison = CharSequence.compare(
                        left.subSequence(leftIndex, leftNext),
                        right.substring(rightIndex, rightNext));
            } else if (leftText) {
                return 1;
            } else if (rightText) {
                return -1;
            } else {
                comparison = Integer.compare(leftInteger, rightInteger);
            }
            if (comparison != 0) {
                return comparison;
            }
            leftIndex = leftNext;
            rightIndex = rightNext;
        }
        if (leftIndex < left.length()) {
            return -1;
        } else if (rightIndex < right.length()) {
            return 1;
        } else {
            return 0;
        }
    }

    private record Metadata(String latest, String release, List<String> versions) {
    }
}
