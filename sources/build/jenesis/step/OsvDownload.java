package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDependencyKey;

public class OsvDownload implements BuildStep {

    private final URI endpoint;

    public OsvDownload() {
        this(URI.create("https://api.osv.dev"));
    }

    private OsvDownload(URI endpoint) {
        this.endpoint = endpoint;
    }

    public OsvDownload endpoint(URI endpoint) {
        return new OsvDownload(endpoint);
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedSet<String> coordinateSet = new TreeSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path index = argument.folder().resolve(DEPENDENCIES);
            if (!Files.exists(index)) {
                continue;
            }
            SequencedProperties dependencies = SequencedProperties.ofFiles(index);
            for (String key : dependencies.stringPropertyNames()) {
                String coordinate = mavenCoordinate(key);
                if (coordinate != null) {
                    coordinateSet.add(coordinate);
                }
            }
        }
        List<String> coordinates = new ArrayList<>(coordinateSet);
        SequencedProperties feed = new SequencedProperties();
        if (!coordinates.isEmpty()) {
            List<List<String>> identifiers = identifiers(post(endpoint.resolve("/v1/querybatch"), queryBatch(coordinates)));
            SequencedMap<String, String> severityById = new LinkedHashMap<>();
            for (List<String> ids : identifiers) {
                for (String id : ids) {
                    if (!severityById.containsKey(id)) {
                        severityById.put(id, severity(get(endpoint.resolve("/v1/vulns/" + id))));
                    }
                }
            }
            for (int index = 0; index < coordinates.size(); index++) {
                List<String> ids = index < identifiers.size() ? identifiers.get(index) : List.of();
                if (ids.isEmpty()) {
                    continue;
                }
                List<String> entries = new ArrayList<>();
                for (String id : ids) {
                    entries.add(id + ":" + severityById.getOrDefault(id, ""));
                }
                feed.setProperty(coordinates.get(index), String.join(",", entries));
            }
        }
        feed.store(context.next().resolve("advisories.properties"));
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    public static List<List<String>> identifiers(String response) {
        List<List<String>> result = new ArrayList<>();
        Object results = navigate(response, "results");
        if (!(results instanceof List<?> list)) {
            return result;
        }
        for (Object element : list) {
            List<String> ids = new ArrayList<>();
            if (element instanceof Map<?, ?> entry && entry.get("vulns") instanceof List<?> vulns) {
                for (Object vuln : vulns) {
                    if (vuln instanceof Map<?, ?> object && object.get("id") instanceof String id) {
                        ids.add(id);
                    }
                }
            }
            result.add(ids);
        }
        return result;
    }

    public static String severity(String response) {
        if (navigate(response, "database_specific") instanceof Map<?, ?> specific
                && specific.get("severity") instanceof String word) {
            return switch (word.toUpperCase(Locale.ROOT)) {
                case "LOW" -> "LOW";
                case "MODERATE", "MEDIUM" -> "MEDIUM";
                case "HIGH" -> "HIGH";
                case "CRITICAL" -> "CRITICAL";
                default -> "";
            };
        }
        return "";
    }

    private static Object navigate(String response, String field) {
        try {
            Object root = new Json(response).parse();
            return root instanceof Map<?, ?> map ? map.get(field) : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String mavenCoordinate(String key) {
        int first = key.indexOf('/');
        int second = first < 0 ? -1 : key.indexOf('/', first + 1);
        int third = second < 0 ? -1 : key.indexOf('/', second + 1);
        if (third < 0 || !key.substring(second + 1, third).equals("maven")) {
            return null;
        }
        try {
            MavenDependencyKey.Versioned parsed = MavenDependencyKey.tryParse(key.substring(third + 1));
            if (parsed.version() == null) {
                return null;
            }
            return parsed.key().groupId() + "/" + parsed.key().artifactId() + "/" + parsed.version();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String queryBatch(List<String> coordinates) {
        StringBuilder builder = new StringBuilder("{\"queries\":[");
        for (int index = 0; index < coordinates.size(); index++) {
            String[] parts = coordinates.get(index).split("/");
            if (index > 0) {
                builder.append(",");
            }
            builder.append("{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"")
                    .append(parts[0]).append(":").append(parts[1])
                    .append("\"},\"version\":\"").append(parts[2]).append("\"}");
        }
        return builder.append("]}").toString();
    }

    private static String post(URI uri, String body) throws IOException {
        HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
        http.setRequestMethod("POST");
        http.setRequestProperty("User-Agent", "Jenesis");
        http.setRequestProperty("Content-Type", "application/json");
        http.setConnectTimeout(10_000);
        http.setReadTimeout(30_000);
        http.setDoOutput(true);
        try (OutputStream out = http.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(http);
    }

    private static String get(URI uri) throws IOException {
        HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
        http.setRequestProperty("User-Agent", "Jenesis");
        http.setConnectTimeout(10_000);
        http.setReadTimeout(30_000);
        return read(http);
    }

    private static String read(HttpURLConnection http) throws IOException {
        int status = http.getResponseCode();
        if (status != 200) {
            throw new IOException("OSV request to " + http.getURL() + " failed with status " + status);
        }
        try (InputStream in = http.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class Json {

        private final String text;
        private int pos;

        private Json(String text) {
            this.text = text;
        }

        private Object parse() {
            skip();
            Object value = value();
            skip();
            return value;
        }

        private Object value() {
            skip();
            return switch (text.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skip();
            if (text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                pos++;
                map.put(key, value());
                skip();
                if (text.charAt(pos++) == '}') {
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skip();
            if (text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skip();
                if (text.charAt(pos++) == ']') {
                    return list;
                }
            }
        }

        private String string() {
            StringBuilder builder = new StringBuilder();
            pos++;
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'u' -> {
                        builder.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> builder.append(escaped);
                }
            }
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        private Object literal(String token, Object value) {
            pos += token.length();
            return value;
        }

        private void skip() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
