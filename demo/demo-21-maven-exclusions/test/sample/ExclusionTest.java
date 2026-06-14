package sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExclusionTest {

    @Test
    void declared_dependency_is_present() {
        assertNotNull(ExclusionTest.class.getClassLoader()
                .getResource("org/apache/commons/text/StringSubstitutor.class"));
    }

    @Test
    void excluded_transitive_is_absent() {
        assertNull(ExclusionTest.class.getClassLoader()
                .getResource("org/apache/commons/lang3/StringUtils.class"));
    }
}
