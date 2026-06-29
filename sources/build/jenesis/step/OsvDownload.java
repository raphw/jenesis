package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Json;
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
        if (!(parse(response) instanceof Map<?, ?> map)) {
            return "";
        }
        if (map.get("database_specific") instanceof Map<?, ?> specific
                && specific.get("severity") instanceof String word
                && !word(word).isEmpty()) {
            return word(word);
        }
        double highest = -1.0;
        if (map.get("severity") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> object && object.get("score") instanceof String vector) {
                    highest = Math.max(highest, cvss(vector));
                }
            }
        }
        return highest < 0 ? "" : band(highest);
    }

    private static String word(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "LOW" -> "LOW";
            case "MODERATE", "MEDIUM" -> "MEDIUM";
            case "HIGH" -> "HIGH";
            case "CRITICAL" -> "CRITICAL";
            default -> "";
        };
    }

    private static String band(double score) {
        if (score >= 9.0) {
            return "CRITICAL";
        }
        if (score >= 7.0) {
            return "HIGH";
        }
        if (score >= 4.0) {
            return "MEDIUM";
        }
        return score >= 0.1 ? "LOW" : "";
    }

    // CVSS v2 and v3.0/v3.1 are closed-form base-score formulas; v4.0 (table-based)
    // is left unscored and falls through to the GitHub severity word when present.
    private static double cvss(String vector) {
        String trimmed = vector.trim();
        if (trimmed.startsWith("CVSS:3.")) {
            return cvss3(trimmed);
        }
        return trimmed.contains("Au:") ? cvss2(trimmed) : -1.0;
    }

    private static double cvss3(String vector) {
        Map<String, String> metric = metrics(vector);
        boolean changed = "C".equals(metric.get("S"));
        double av = switch (metric.getOrDefault("AV", "")) {
            case "N" -> 0.85; case "A" -> 0.62; case "L" -> 0.55; case "P" -> 0.2; default -> -1;
        };
        double ac = switch (metric.getOrDefault("AC", "")) {
            case "L" -> 0.77; case "H" -> 0.44; default -> -1;
        };
        double pr = switch (metric.getOrDefault("PR", "")) {
            case "N" -> 0.85;
            case "L" -> changed ? 0.68 : 0.62;
            case "H" -> changed ? 0.5 : 0.27;
            default -> -1;
        };
        double ui = switch (metric.getOrDefault("UI", "")) {
            case "N" -> 0.85; case "R" -> 0.62; default -> -1;
        };
        double c = impact3(metric.getOrDefault("C", ""));
        double i = impact3(metric.getOrDefault("I", ""));
        double a = impact3(metric.getOrDefault("A", ""));
        if (av < 0 || ac < 0 || pr < 0 || ui < 0 || c < 0 || i < 0 || a < 0) {
            return -1.0;
        }
        double iss = 1 - (1 - c) * (1 - i) * (1 - a);
        double impact = changed
                ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15)
                : 6.42 * iss;
        if (impact <= 0) {
            return 0.0;
        }
        double exploitability = 8.22 * av * ac * pr * ui;
        return roundUp(Math.min((changed ? 1.08 : 1.0) * (impact + exploitability), 10));
    }

    private static double impact3(String value) {
        return switch (value) {
            case "H" -> 0.56; case "L" -> 0.22; case "N" -> 0.0; default -> -1;
        };
    }

    private static double cvss2(String vector) {
        Map<String, String> metric = metrics(vector);
        double av = switch (metric.getOrDefault("AV", "")) {
            case "L" -> 0.395; case "A" -> 0.646; case "N" -> 1.0; default -> -1;
        };
        double ac = switch (metric.getOrDefault("AC", "")) {
            case "H" -> 0.35; case "M" -> 0.61; case "L" -> 0.71; default -> -1;
        };
        double au = switch (metric.getOrDefault("Au", "")) {
            case "M" -> 0.45; case "S" -> 0.56; case "N" -> 0.704; default -> -1;
        };
        double c = impact2(metric.getOrDefault("C", ""));
        double i = impact2(metric.getOrDefault("I", ""));
        double a = impact2(metric.getOrDefault("A", ""));
        if (av < 0 || ac < 0 || au < 0 || c < 0 || i < 0 || a < 0) {
            return -1.0;
        }
        double impact = 10.41 * (1 - (1 - c) * (1 - i) * (1 - a));
        double exploitability = 20 * av * ac * au;
        double base = ((0.6 * impact) + (0.4 * exploitability) - 1.5) * (impact == 0 ? 0 : 1.176);
        return Math.round(base * 10.0) / 10.0;
    }

    private static double impact2(String value) {
        return switch (value) {
            case "N" -> 0.0; case "P" -> 0.275; case "C" -> 0.660; default -> -1;
        };
    }

    private static double roundUp(double input) {
        long scaled = Math.round(input * 100_000);
        return scaled % 10_000 == 0 ? scaled / 100_000.0 : (Math.floorDiv(scaled, 10_000) + 1) / 10.0;
    }

    private static Map<String, String> metrics(String vector) {
        Map<String, String> metric = new HashMap<>();
        for (String part : vector.split("/")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                metric.put(part.substring(0, colon), part.substring(colon + 1));
            }
        }
        return metric;
    }

    private static Object parse(String response) {
        try {
            return Json.parse(response);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static Object navigate(String response, String field) {
        return parse(response) instanceof Map<?, ?> map ? map.get(field) : null;
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

}
