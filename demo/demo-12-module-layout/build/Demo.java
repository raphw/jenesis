package build;

import build.jenesis.Project;

public class Demo {

    static void main(String[] args) throws Exception {
        // Select the pure MODULAR layout explicitly, rather than letting the
        // default AUTO detection settle on MODULAR_TO_MAVEN. The MODULAR layout
        // resolves dependencies by Java module name against the Jenesis module
        // repository and emits a plain modular jar - no pom.xml is produced.
        new Project()
                .layout(Project.Layout.MODULAR)
                .build(args);
    }
}
