package build.jenesis.project;

import module java.base;

public record JaCoCo() implements ObservabilityEngine {

    @Override
    public String name() {
        return "jacoco";
    }

    @Override
    public SequencedMap<String, String> coordinates() {
        SequencedMap<String, String> coordinates = new LinkedHashMap<>();
        coordinates.put("maven/org.jacoco/org.jacoco.agent/jar/runtime", "RELEASE");
        return coordinates;
    }

    @Override
    public List<String> commands(SequencedMap<String, Path> resolved, Path output) {
        Path agent = resolved.get("maven/org.jacoco/org.jacoco.agent/jar/runtime");
        if (agent == null) {
            return List.of();
        }
        return List.of("-javaagent:"
                + agent.toAbsolutePath()
                + "=destfile="
                + output.resolve("jacoco.exec").toAbsolutePath()
                + ",output=file,append=false");
    }
}
