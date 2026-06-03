package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.maven.MavenDependencyKey;
import build.jenesis.maven.MavenDependencyName;
import build.jenesis.maven.MavenDependencyScope;
import build.jenesis.maven.MavenDependencyValue;
import build.jenesis.maven.MavenPomEmitter;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenPomEmitterTest {

    @Test
    public void can_emit_pom_with_defaults() throws IOException {
        StringWriter writer = new StringWriter();
        new MavenPomEmitter().emit("group",
                "artifact",
                "version",
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
    public void can_emit_pom_with_metadata() throws IOException {
        StringWriter writer = new StringWriter();
        new MavenPomEmitter().emit("group",
                "artifact",
                "version",
                new LinkedHashMap<>(),
                new MavenPomEmitter.Metadata(
                        "Project Name",
                        "Project description.",
                        "https://example.com/project",
                        List.of(new MavenPomEmitter.Metadata.License(
                                "Apache-2.0",
                                "https://www.apache.org/licenses/LICENSE-2.0.txt")),
                        List.of(new MavenPomEmitter.Metadata.Developer(
                                "alice",
                                "Alice Example",
                                "alice@example.com")),
                        new MavenPomEmitter.Metadata.Scm(
                                "scm:git:https://example.com/project.git",
                                "scm:git:git@example.com:project.git",
                                "https://example.com/project"))).accept(writer);
        assertThat(writer.toString()).isEqualTo("""
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>version</version>
                    <name>Project Name</name>
                    <description>Project description.</description>
                    <url>https://example.com/project</url>
                    <licenses>
                        <license>
                            <name>Apache-2.0</name>
                            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                        </license>
                    </licenses>
                    <developers>
                        <developer>
                            <id>alice</id>
                            <name>Alice Example</name>
                            <email>alice@example.com</email>
                        </developer>
                    </developers>
                    <scm>
                        <connection>scm:git:https://example.com/project.git</connection>
                        <developerConnection>scm:git:git@example.com:project.git</developerConnection>
                        <url>https://example.com/project</url>
                    </scm>
                </project>
                """);
    }

    @Test
    public void can_emit_pom() throws IOException {
        StringWriter writer = new StringWriter();
        new MavenPomEmitter().emit("group",
                "artifact",
                "version",
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

    @Test
    public void scm_developer_connection_falls_back_to_connection() throws IOException {
        StringWriter writer = new StringWriter();
        new MavenPomEmitter().emit("group",
                "artifact",
                "version",
                new LinkedHashMap<>(),
                new MavenPomEmitter.Metadata(
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        new MavenPomEmitter.Metadata.Scm(
                                "scm:git:https://example.com/project.git",
                                null,
                                null))).accept(writer);
        assertThat(writer.toString()).isEqualTo("""
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>version</version>
                    <scm>
                        <connection>scm:git:https://example.com/project.git</connection>
                        <developerConnection>scm:git:https://example.com/project.git</developerConnection>
                    </scm>
                </project>
                """);
    }
}
