package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JsonTest {

    @Test
    public void parses_an_object_into_an_insertion_ordered_map() {
        Object value = Json.parse("{\"b\": 1, \"a\": 2}");
        assertThat(value).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) value;
        Iterator<?> keys = map.keySet().iterator();
        assertThat(keys.next()).isEqualTo("b");
        assertThat(keys.next()).isEqualTo("a");
        assertThat(map.get("a")).isEqualTo(2.0);
    }

    @Test
    public void parses_an_array_of_mixed_values() {
        Object value = Json.parse("[1, \"x\", true, false, null]");
        assertThat(value).isInstanceOf(List.class);
        List<?> list = (List<?>) value;
        assertThat(list).hasSize(5);
        assertThat(list.get(0)).isEqualTo(1.0);
        assertThat(list.get(1)).isEqualTo("x");
        assertThat(list.get(2)).isEqualTo(true);
        assertThat(list.get(3)).isEqualTo(false);
        assertThat(list.get(4)).isNull();
    }

    @Test
    public void parses_numbers_as_doubles() {
        assertThat(Json.parse("42")).isEqualTo(42.0);
        assertThat(Json.parse("-3.5")).isEqualTo(-3.5);
        assertThat(Json.parse("1e3")).isEqualTo(1000.0);
    }

    @Test
    public void unescapes_strings() {
        assertThat(Json.parse("\"a\\tb\\nc\"")).isEqualTo("a\tb\nc");
        assertThat(Json.parse("\"quote: \\\" backslash: \\\\\"")).isEqualTo("quote: \" backslash: \\");
    }

    @Test
    public void unescapes_a_unicode_escape() {
        assertThat(Json.parse("\"\\u00e9\"")).isEqualTo(String.valueOf((char) 0xe9));
    }

    @Test
    public void parses_nested_structures() {
        Map<?, ?> root = (Map<?, ?>) Json.parse("{\"a\": [ {\"b\": 2} ]}");
        List<?> a = (List<?>) root.get("a");
        Map<?, ?> first = (Map<?, ?>) a.getFirst();
        assertThat(first.get("b")).isEqualTo(2.0);
    }

    @Test
    public void ignores_surrounding_whitespace() {
        Map<?, ?> map = (Map<?, ?>) Json.parse("  \n { \"a\" : 1 } \t ");
        assertThat(map.get("a")).isEqualTo(1.0);
    }

    @Test
    public void parses_empty_object_and_array() {
        assertThat((Map<?, ?>) Json.parse("{}")).isEmpty();
        assertThat((List<?>) Json.parse("[]")).isEmpty();
    }

    @Test
    public void duplicate_keys_keep_the_last_value() {
        Map<?, ?> map = (Map<?, ?>) Json.parse("{\"a\": 1, \"a\": 2}");
        assertThat(map).hasSize(1);
        assertThat(map.get("a")).isEqualTo(2.0);
    }

    @Test
    public void rejects_a_deeply_nested_array_without_a_stack_overflow() {
        String bomb = "[".repeat(100_000);
        assertThatThrownBy(() -> Json.parse(bomb))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nesting");
    }

    @Test
    public void rejects_a_deeply_nested_object_without_a_stack_overflow() {
        String bomb = "{\"a\":".repeat(100_000);
        assertThatThrownBy(() -> Json.parse(bomb)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void truncated_input_fails_with_a_runtime_exception() {
        assertThatThrownBy(() -> Json.parse("{\"a\":")).isInstanceOf(RuntimeException.class);
    }

    @Test
    public void an_unterminated_string_fails_with_a_runtime_exception() {
        assertThatThrownBy(() -> Json.parse("\"abc")).isInstanceOf(RuntimeException.class);
    }
}
