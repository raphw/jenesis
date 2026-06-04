package build.jenesis;

import module java.base;

public final class DependencyTreeReport {

    private static final int[] GRADIENT = {
            39, 44, 48, 83, 113, 148, 184, 214, 208, 203, 168, 134};

    private final List<Tree> trees = Collections.synchronizedList(new ArrayList<>());
    private final Set<Resolution> resolutions = Collections.synchronizedSet(new LinkedHashSet<>());

    public Supplier<ResolutionListener> supplier() {
        return Collector::new;
    }

    public void print(PrintStream out) {
        List<Tree> snapshot;
        synchronized (trees) {
            snapshot = new ArrayList<>(trees);
        }
        List<Set<String>> signatures = new ArrayList<>();
        for (Tree tree : snapshot) {
            Set<String> signature = new HashSet<>();
            for (Edge edge : tree.edges()) {
                if (edge.followed()) {
                    signature.add(edge.parent() + " -> " + edge.coordinate());
                }
            }
            signatures.add(signature);
        }
        List<Tree> surviving = new ArrayList<>();
        for (int index = 0; index < snapshot.size(); index++) {
            Set<String> signature = signatures.get(index);
            if (!signature.isEmpty() && !subsumed(signature, index, signatures)) {
                surviving.add(snapshot.get(index));
            }
        }
        if (!surviving.isEmpty()) {
            out.println();
            out.println(BuildExecutorCallback.YELLOW + "Dependency tree:" + BuildExecutorCallback.RESET);
            int[] color = {0};
            for (Tree tree : surviving) {
                out.println();
                out.print(render(tree, color));
            }
        }
        List<Resolution> resolved;
        synchronized (resolutions) {
            resolved = new ArrayList<>(resolutions);
        }
        resolved.sort(Comparator.comparing(Resolution::coordinate).thenComparing(Resolution::version));
        if (!resolved.isEmpty()) {
            out.println();
            out.println(BuildExecutorCallback.YELLOW + "Resolved dependencies:" + BuildExecutorCallback.RESET);
            for (Resolution resolution : resolved) {
                out.println("  " + resolution.coordinate() + paint(245, " -> " + resolution.version()));
            }
        }
    }

    private static boolean subsumed(Set<String> signature, int index, List<Set<String>> signatures) {
        for (int other = 0; other < signatures.size(); other++) {
            if (other == index) {
                continue;
            }
            Set<String> candidate = signatures.get(other);
            if (candidate.containsAll(signature)
                    && (candidate.size() > signature.size() || other < index)) {
                return true;
            }
        }
        return false;
    }

    private static String render(Tree tree, int[] colorIndex) {
        SequencedMap<String, List<Edge>> children = new LinkedHashMap<>();
        List<Edge> roots = new ArrayList<>();
        for (Edge edge : tree.edges()) {
            if (edge.parent() == null) {
                if (edge.followed()) {
                    roots.add(edge);
                }
            } else {
                children.computeIfAbsent(edge.parent(), _ -> new ArrayList<>()).add(edge);
            }
        }
        StringBuilder out = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (Edge root : roots) {
            int treeColor = GRADIENT[colorIndex[0]++ % GRADIENT.length];
            out.append(label(root, tree.resolved(), treeColor, true)).append(System.lineSeparator());
            children(out, root.coordinate(), children, "", seen, tree.resolved(), treeColor);
        }
        return out.toString();
    }

    private static void children(StringBuilder out,
                                 String coordinate,
                                 SequencedMap<String, List<Edge>> children,
                                 String indent,
                                 Set<String> seen,
                                 Map<String, String> resolved,
                                 int treeColor) {
        List<Edge> next = children.getOrDefault(coordinate, List.of());
        for (int index = 0; index < next.size(); index++) {
            boolean last = index == next.size() - 1;
            Edge edge = next.get(index);
            out.append(paint(treeColor, indent + (last ? "└─ " : "├─ ")))
                    .append(label(edge, resolved, treeColor, false))
                    .append(System.lineSeparator());
            if (edge.followed() && seen.add(edge.coordinate())) {
                children(out, edge.coordinate(), children, indent + (last ? "   " : "│  "), seen, resolved, treeColor);
            }
        }
    }

    private static String label(Edge edge, Map<String, String> resolved, int treeColor, boolean root) {
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

    private final class Collector implements ResolutionListener {

        private final List<Edge> edges = new ArrayList<>();
        private final Map<String, String> resolved = new HashMap<>();

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
            resolutions.add(new Resolution(coordinate, version));
        }

        @Override
        public void onResolved() {
            if (!edges.isEmpty()) {
                trees.add(new Tree(List.copyOf(edges), Map.copyOf(resolved)));
            }
        }
    }

    private record Tree(List<Edge> edges, Map<String, String> resolved) {
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
