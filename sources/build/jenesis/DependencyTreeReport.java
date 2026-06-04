package build.jenesis;

import module java.base;

public final class DependencyTreeReport implements ResolutionListener {

    private static final int[] GRADIENT = {
            39, 44, 48, 83, 113, 148, 184, 214, 208, 203, 168, 134};

    private final PrintStream out;
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, String> resolved = new HashMap<>();

    public DependencyTreeReport() {
        this(System.out);
    }

    public DependencyTreeReport(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onDependency(String prefix,
                             String parent,
                             String coordinate,
                             String version,
                             String scope,
                             boolean followed,
                             Supplier<ResolutionContext> context) {
        edges.add(new Edge(parent, coordinate, version, scope, followed, context));
    }

    @Override
    public void onResolution(String prefix,
                             String coordinate,
                             String version) {
        resolved.put(coordinate, version);
    }

    @Override
    public void onResolved() {
        if (edges.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(System.lineSeparator())
                .append(BuildExecutorCallback.YELLOW).append("Dependency tree:").append(BuildExecutorCallback.RESET)
                .append(System.lineSeparator())
                .append(render());
        List<Resolution> resolutions = new ArrayList<>();
        resolved.forEach((coordinate, version) -> resolutions.add(new Resolution(coordinate, version)));
        resolutions.sort(Comparator.comparing(Resolution::coordinate).thenComparing(Resolution::version));
        if (!resolutions.isEmpty()) {
            builder.append(System.lineSeparator())
                    .append(BuildExecutorCallback.YELLOW).append("Resolved dependencies:").append(BuildExecutorCallback.RESET)
                    .append(System.lineSeparator());
            for (Resolution resolution : resolutions) {
                builder.append("  ")
                        .append(resolution.coordinate())
                        .append(paint(245, " -> " + resolution.version()))
                        .append(System.lineSeparator());
            }
        }
        synchronized (out) {
            out.print(builder);
        }
    }

    private String render() {
        SequencedMap<String, List<Edge>> children = new LinkedHashMap<>();
        List<Edge> roots = new ArrayList<>();
        for (Edge edge : edges) {
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
        for (Edge root : roots) {
            int treeColor = GRADIENT[colorIndex[0]++ % GRADIENT.length];
            builder.append(label(root, treeColor, true)).append(System.lineSeparator());
            children(builder, root.coordinate(), children, "", seen, treeColor);
        }
        return builder.toString();
    }

    private void children(StringBuilder builder,
                          String coordinate,
                          SequencedMap<String, List<Edge>> children,
                          String indent,
                          Set<String> seen,
                          int treeColor) {
        List<Edge> next = children.getOrDefault(coordinate, List.of());
        for (int index = 0; index < next.size(); index++) {
            boolean last = index == next.size() - 1;
            Edge edge = next.get(index);
            builder.append(paint(treeColor, indent + (last ? "└─ " : "├─ ")))
                    .append(label(edge, treeColor, false))
                    .append(System.lineSeparator());
            if (edge.followed() && seen.add(edge.coordinate())) {
                children(builder, edge.coordinate(), children, indent + (last ? "   " : "│  "), seen, treeColor);
            }
        }
    }

    private String label(Edge edge, int treeColor, boolean root) {
        String coordinate = edge.coordinate(), version = edge.version(), key = coordinate, discovered = null;
        if (version != null && !version.isEmpty() && coordinate.endsWith("/" + version)) {
            key = coordinate.substring(0, coordinate.length() - version.length() - 1);
            discovered = version;
        }
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
            String promoted = resolved.get(key);
            if (edge.followed() && promoted != null && !promoted.equals(discovered)) {
                line.append(paint(173, " -> " + promoted));
            }
        }
        if (edge.scope() != null) {
            line.append(' ').append(paint(67, "[" + edge.scope() + "]"));
        }
        ResolutionContext context = edge.followed() && edge.context() != null ? edge.context().get() : null;
        if (context != null) {
            StringBuilder meta = new StringBuilder();
            if (context.moduleName() != null) {
                meta.append("module ").append(context.moduleName());
                if (context.moduleVersion() != null) {
                    meta.append('@').append(context.moduleVersion());
                }
                if (Boolean.TRUE.equals(context.automaticModule())) {
                    meta.append(", automatic");
                }
            } else if (Boolean.TRUE.equals(context.automaticModule())) {
                meta.append("automatic module");
            }
            if (!meta.isEmpty()) {
                line.append(' ').append(paint(109, "(" + meta + ")"));
            }
            if (context.resolvedCoordinate() != null) {
                line.append(' ').append(paint(108, "=> " + context.resolvedCoordinate()));
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

    private record Edge(String parent,
                        String coordinate,
                        String version,
                        String scope,
                        boolean followed,
                        Supplier<ResolutionContext> context) {
    }

    private record Resolution(String coordinate, String version) {
    }
}
