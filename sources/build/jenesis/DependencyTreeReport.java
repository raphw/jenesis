package build.jenesis;

import module java.base;

public final class DependencyTreeReport {

    private static final int[] GRADIENT = {
            39, 44, 48, 83, 113, 148, 184, 214, 208, 203, 168, 134};

    private final PrintStream out;

    public DependencyTreeReport() {
        this(System.out);
    }

    public DependencyTreeReport(PrintStream out) {
        this.out = out;
    }

    public void render(Resolver.Resolution resolution) {
        render(resolution, "Dependency tree:");
    }

    public void render(Resolver.Resolution resolution, String title) {
        List<Resolver.Edge> edges = resolution.edges();
        if (edges.isEmpty()) {
            return;
        }
        SequencedMap<String, Resolver.Vertex> nodes = resolution.vertices();
        StringBuilder builder = new StringBuilder();
        builder.append(System.lineSeparator())
                .append(BuildExecutorCallback.YELLOW).append(title).append(BuildExecutorCallback.RESET)
                .append(System.lineSeparator())
                .append(render(edges, nodes));
        if (!nodes.isEmpty()) {
            builder.append(System.lineSeparator())
                    .append(BuildExecutorCallback.YELLOW).append("Resolved dependencies:").append(BuildExecutorCallback.RESET)
                    .append(System.lineSeparator());
            nodes.forEach((coordinate, node) -> builder.append("  ")
                    .append(coordinate)
                    .append(paint(245, " -> " + node.resolvedVersion()))
                    .append(System.lineSeparator()));
        }
        synchronized (out) {
            out.print(builder);
        }
    }

    public void summary(SequencedMap<String, Resolver.Vertex> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        int total = nodes.size();
        SequencedMap<String, Integer> licenses = new LinkedHashMap<>();
        SequencedMap<String, String> categories = new LinkedHashMap<>();
        SequencedMap<String, Integer> permissiveness = new LinkedHashMap<>();
        Set<String> implied = new LinkedHashSet<>();
        int named = 0, automatic = 0, plain = 0, multiple = 0;
        for (Resolver.Vertex node : nodes.values()) {
            List<License> declared = node.licenses().stream()
                    .filter(entry -> entry.id() != null || entry.name() != null || entry.url() != null)
                    .toList();
            Set<String> labels = new LinkedHashSet<>();
            for (License entry : declared) {
                labels.add(licenseLabel(entry));
            }
            implied.addAll(labels);
            if (labels.size() > 1) {
                multiple++;
            }
            License primary = declared.stream()
                    .max(Comparator.comparingInt(entry -> permissiveness(entry.category())))
                    .orElse(null);
            String label = primary == null ? "unknown" : licenseLabel(primary);
            String category = primary == null ? null : primary.category();
            licenses.merge(label, 1, Integer::sum);
            categories.putIfAbsent(label, category);
            permissiveness.merge(category == null ? "unknown" : category, 1, Integer::sum);
            if (node.automatic()) {
                automatic++;
            } else if (node.module() != null) {
                named++;
            } else {
                plain++;
            }
        }
        int width = "non-modular".length();
        for (String label : licenses.keySet()) {
            width = Math.max(width, label.length());
        }
        for (String label : permissiveness.keySet()) {
            width = Math.max(width, label.length());
        }
        String tally = implied.size() + (implied.size() == 1 ? " license" : " licenses") + " implied";
        if (multiple > 0) {
            tally += ", " + multiple + (multiple == 1 ? " dependency offers" : " dependencies offer") + " multiple";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(System.lineSeparator())
                .append(BuildExecutorCallback.YELLOW).append("Licenses:").append(BuildExecutorCallback.RESET)
                .append(System.lineSeparator())
                .append("  ").append(paint(245, tally)).append(System.lineSeparator());
        for (Map.Entry<String, Integer> entry : distribution(licenses)) {
            builder.append(row(entry.getKey(), entry.getValue(), total, width, categoryColor(categories.get(entry.getKey()))));
        }
        builder.append(System.lineSeparator())
                .append(BuildExecutorCallback.YELLOW).append("Permissiveness:").append(BuildExecutorCallback.RESET)
                .append(System.lineSeparator());
        for (Map.Entry<String, Integer> entry : distribution(permissiveness)) {
            builder.append(row(entry.getKey(), entry.getValue(), total, width, categoryColor(entry.getKey())));
        }
        builder.append(System.lineSeparator())
                .append(BuildExecutorCallback.YELLOW).append("Modules:").append(BuildExecutorCallback.RESET)
                .append(System.lineSeparator())
                .append(row("named", named, total, width, 71))
                .append(row("automatic", automatic, total, width, 214))
                .append(row("non-modular", plain, total, width, 245));
        synchronized (out) {
            out.print(builder);
        }
    }

    private static List<Map.Entry<String, Integer>> distribution(SequencedMap<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
    }

    private static String licenseLabel(License license) {
        return license.id() != null ? license.id()
                : license.name() != null ? license.name() : license.url();
    }

    private static int permissiveness(String category) {
        if (category == null) {
            return 0;
        }
        return switch (category) {
            case "public-domain" -> 6;
            case "permissive" -> 5;
            case "weak-copyleft" -> 4;
            case "strong-copyleft" -> 3;
            case "network-copyleft" -> 2;
            default -> 1;
        };
    }

    private static int categoryColor(String category) {
        if (category == null) {
            return 245;
        }
        return switch (category) {
            case "permissive", "public-domain" -> 71;
            case "weak-copyleft" -> 214;
            case "strong-copyleft", "network-copyleft" -> 167;
            default -> 245;
        };
    }

    private static String row(String label, int count, int total, int width, int color) {
        int bar = count == 0 ? 0 : Math.max(1, count * 20 / total);
        return "  " + paint(color, String.format(Locale.ROOT, "%-" + width + "s", label))
                + "  " + paint(245, String.format(Locale.ROOT, "%3d (%3d%%)", count, count * 100 / total))
                + "  " + paint(color, "█".repeat(bar))
                + System.lineSeparator();
    }

    private String render(List<Resolver.Edge> edges, SequencedMap<String, Resolver.Vertex> nodes) {
        SequencedMap<String, List<Resolver.Edge>> children = new LinkedHashMap<>();
        List<Resolver.Edge> roots = new ArrayList<>();
        for (Resolver.Edge edge : edges) {
            if (edge.parent() == null) {
                if (edge.followed()) {
                    roots.add(edge);
                }
            } else {
                children.computeIfAbsent(edge.parent(), _ -> new ArrayList<>()).add(edge);
            }
        }
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int[] colorIndex = {0};
        for (Resolver.Edge root : roots) {
            int treeColor = GRADIENT[colorIndex[0]++ % GRADIENT.length];
            builder.append(label(root, nodes, treeColor, true)).append(System.lineSeparator());
            children(builder, root.coordinate(), children, nodes, "", seen, treeColor);
        }
        return builder.toString();
    }

    private void children(StringBuilder builder,
                          String coordinate,
                          SequencedMap<String, List<Resolver.Edge>> children,
                          SequencedMap<String, Resolver.Vertex> nodes,
                          String indent,
                          Set<String> seen,
                          int treeColor) {
        List<Resolver.Edge> next = children.getOrDefault(coordinate, List.of());
        for (int index = 0; index < next.size(); index++) {
            boolean last = index == next.size() - 1;
            Resolver.Edge edge = next.get(index);
            builder.append(paint(treeColor, indent + (last ? "└─ " : "├─ ")))
                    .append(label(edge, nodes, treeColor, false))
                    .append(System.lineSeparator());
            if (edge.followed() && seen.add(edge.coordinate())) {
                children(builder, edge.coordinate(), children, nodes, indent + (last ? "   " : "│  "), seen, treeColor);
            }
        }
    }

    private String label(Resolver.Edge edge, SequencedMap<String, Resolver.Vertex> nodes, int treeColor, boolean root) {
        String coordinate = edge.coordinate(), version = edge.version(), key = coordinate, discovered = null;
        if (version != null && !version.isEmpty() && coordinate.endsWith("/" + version)) {
            key = coordinate.substring(0, coordinate.length() - version.length() - 1);
            discovered = version;
        }
        Resolver.Vertex node = edge.followed() ? nodes.get(key) : null;
        StringBuilder line = new StringBuilder();
        if (!edge.followed()) {
            line.append(paint(240, key));
        } else if (root) {
            line.append("\033[1;38;5;").append(treeColor).append('m').append(key).append(BuildExecutorCallback.RESET);
        } else {
            line.append(key);
        }
        if (discovered != null) {
            line.append(' ').append(paint(245, discovered));
            String promoted = node == null ? null : node.resolvedVersion();
            if (promoted != null && !promoted.equals(discovered)) {
                line.append(paint(173, " -> " + promoted));
            }
        }
        if (edge.scope() != null) {
            line.append(' ').append(paint(67, "[" + edge.scope() + "]"));
        }
        if (node != null) {
            StringBuilder meta = new StringBuilder();
            if (node.module() != null) {
                meta.append("module ").append(node.module());
                if (node.automatic()) {
                    meta.append(", automatic");
                }
            } else if (node.automatic()) {
                meta.append("automatic module");
            }
            if (!meta.isEmpty()) {
                line.append(' ').append(paint(109, "(" + meta + ")"));
            }
            String names = node.licenses().stream()
                    .map(license -> license.id() != null ? license.id()
                            : license.name() != null ? license.name() : license.url())
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            if (!names.isEmpty()) {
                line.append(' ').append(paint(142, "{" + names + "}"));
            }
        }
        if (!edge.followed()) {
            line.append(' ').append(paint(240, "(*)"));
        }
        return line.toString();
    }

    private static String paint(int code, String text) {
        return "\033[38;5;" + code + "m" + text + BuildExecutorCallback.RESET;
    }
}
