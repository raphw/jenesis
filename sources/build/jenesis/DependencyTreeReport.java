package build.jenesis;

import module java.base;

public final class DependencyTreeReport {

    private final List<List<Edge>> trees = Collections.synchronizedList(new ArrayList<>());

    public Supplier<ResolutionListener> supplier() {
        return Collector::new;
    }

    public void print(PrintStream out) {
        List<List<Edge>> snapshot;
        synchronized (trees) {
            snapshot = new ArrayList<>(trees);
        }
        List<Set<String>> signatures = new ArrayList<>();
        for (List<Edge> tree : snapshot) {
            Set<String> signature = new HashSet<>();
            for (Edge edge : tree) {
                if (edge.followed()) {
                    signature.add(edge.parent() + " -> " + edge.coordinate());
                }
            }
            signatures.add(signature);
        }
        Set<String> rendered = new LinkedHashSet<>();
        List<String> blocks = new ArrayList<>();
        for (int index = 0; index < snapshot.size(); index++) {
            Set<String> signature = signatures.get(index);
            if (signature.isEmpty() || subsumed(signature, index, signatures)) {
                continue;
            }
            String block = render(snapshot.get(index));
            if (!block.isEmpty() && rendered.add(block)) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return;
        }
        out.println();
        out.println("Dependency tree:");
        for (String block : blocks) {
            out.println();
            out.print(block);
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

    private static String render(List<Edge> tree) {
        SequencedMap<String, List<Edge>> children = new LinkedHashMap<>();
        List<Edge> roots = new ArrayList<>();
        for (Edge edge : tree) {
            if (!edge.followed()) {
                continue;
            }
            if (edge.parent() == null) {
                roots.add(edge);
            } else {
                children.computeIfAbsent(edge.parent(), _ -> new ArrayList<>()).add(edge);
            }
        }
        StringBuilder out = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (Edge root : roots) {
            out.append(label(root)).append(System.lineSeparator());
            children(out, root.coordinate(), children, "", seen);
        }
        return out.toString();
    }

    private static void children(StringBuilder out,
                                 String coordinate,
                                 SequencedMap<String, List<Edge>> children,
                                 String indent,
                                 Set<String> seen) {
        if (!seen.add(coordinate)) {
            return;
        }
        List<Edge> next = children.getOrDefault(coordinate, List.of());
        for (int index = 0; index < next.size(); index++) {
            boolean last = index == next.size() - 1;
            Edge edge = next.get(index);
            out.append(indent).append(last ? "└─ " : "├─ ").append(label(edge)).append(System.lineSeparator());
            children(out, edge.coordinate(), children, indent + (last ? "   " : "│  "), seen);
        }
    }

    private static String label(Edge edge) {
        StringBuilder line = new StringBuilder(edge.coordinate());
        if (edge.scope() != null) {
            line.append("  [").append(edge.scope()).append(']');
        }
        ResolutionContext context = edge.context() == null ? null : edge.context().get();
        if (context != null) {
            if (context.moduleName() != null) {
                line.append("  (module ").append(context.moduleName());
                if (context.moduleVersion() != null) {
                    line.append('@').append(context.moduleVersion());
                }
                if (Boolean.TRUE.equals(context.automaticModule())) {
                    line.append(", automatic");
                }
                line.append(')');
            } else if (Boolean.TRUE.equals(context.automaticModule())) {
                line.append("  (automatic module)");
            }
            if (context.resolvedCoordinate() != null) {
                line.append("  -> ").append(context.resolvedCoordinate());
            }
        }
        return line.toString();
    }

    private final class Collector implements ResolutionListener {

        private final List<Edge> edges = new ArrayList<>();

        @Override
        public void onDependency(String prefix,
                                 String parent,
                                 String coordinate,
                                 String version,
                                 String scope,
                                 boolean followed,
                                 Supplier<ResolutionContext> context) {
            edges.add(new Edge(parent, coordinate, scope, followed, context));
        }

        @Override
        public void onResolved() {
            if (!edges.isEmpty()) {
                trees.add(List.copyOf(edges));
            }
        }
    }

    private record Edge(String parent,
                        String coordinate,
                        String scope,
                        boolean followed,
                        Supplier<ResolutionContext> context) {
    }
}
