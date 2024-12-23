package build.buildbuddy.maven;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenPomEmitterTest {

    @Test
    public void can_emit_pom() throws IOException {
        StringWriter writer = new StringWriter();
        new MavenPomEmitter().emit("group",
                "artifact",
                "version",
                null,
                List.of(new MavenDependency("other",
                        "artifact",
                        "version",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false))).accept(writer);
        assertThat(writer.toString()).isEqualTo("""
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>version</version>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>version</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
    }
}
