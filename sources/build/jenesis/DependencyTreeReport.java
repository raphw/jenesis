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
                    .map(license -> license.name() != null ? license.name() : license.url())
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
