package build.buildbuddy.test.maven;

import build.buildbuddy.maven.MavenDependencyKey;
import build.buildbuddy.maven.MavenDependencyName;
import build.buildbuddy.maven.MavenDependencyScope;
import build.buildbuddy.maven.MavenDependencyValue;
import build.buildbuddy.maven.MavenPomEmitter;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenPomEmitterTest {

    @Test
    public void can_emit_pom_with_defaults() throws IOException {
        StringWriter writer = new StringWriter();
        new MavenPomEmitter().emit("group",
                "artifact",
                "version",
                null,
                new LinkedHashMap<>(Map.of(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("version",
                                MavenDependencyScope.COMPILE,
                                null,
                                null,
                                null)))).accept(writer);
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

    @Test
    public void can_emit_pom() throws IOException {
        StringWriter writer = new StringWriter();
        new MavenPomEmitter().emit("group",
                "artifact",
                "version",
                null,
                new LinkedHashMap<>(Map.of(
                        new MavenDependencyKey("other", "artifact", "test-jar", "classifier"),
                        new MavenDependencyValue("version",
                                MavenDependencyScope.SYSTEM,
                                Path.of("file.jar"),
                                List.of(new MavenDependencyName("group", "artifact")),
                                false)))).accept(writer);
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
                            <type>test-jar</type>
                            <classifier>classifier</classifier>
                            <scope>system</scope>
                            <systemPath>file.jar</systemPath>
                            <optional>false</optional>
                            <exclusions>
                                <exclusion>
                                    <groupId>group</groupId>
                                    <artifactId>artifact</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                    </dependencies>
                </project>
                """);
    }
}
