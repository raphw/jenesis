package build.buildbuddy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class SequencedProperties extends Properties {

    private final SequencedMap<Object, Object> delegate = new LinkedHashMap<>();

    @Override
    public void store(Writer writer, String comments) throws IOException {
        super.store(new CommentSuppressingWriter(writer), comments);
    }

    @Override
    public String getProperty(String key) {
        if (delegate.get(key) instanceof String value) {
            return value;
        } else if (defaults != null) {
            return defaults.getProperty(key);
        } else {
            return null;
        }
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public Enumeration<Object> keys() {
        return Collections.enumeration(delegate.keySet());
    }

    @Override
    public Enumeration<Object> elements() {
        return Collections.enumeration(delegate.values());
    }

    @Override
    public boolean contains(Object value) {
        return delegate.containsValue(value);
    }

    @Override
    public boolean containsValue(Object value) {
        return delegate.containsValue(value);
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    public Object get(Object key) {
        return delegate.get(key);
    }

    @Override
    public Object put(Object key, Object value) {
        return delegate.put(key, value);
    }

    @Override
    public Object remove(Object key) {
        return delegate.remove(key);
    }

    @Override
    public void putAll(Map<?, ?> t) {
        delegate.putAll(t);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public Set<Object> keySet() {
        return delegate.keySet();
    }

    @Override
    public Collection<Object> values() {
        return delegate.values();
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        return delegate.entrySet();
    }

    @Override
    public boolean equals(Object o) {
        return delegate.equals(o);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        return delegate.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<? super Object, ? super Object> action) {
        delegate.forEach(action);
    }

    @Override
    public void replaceAll(BiFunction<? super Object, ? super Object, ?> function) {
        delegate.replaceAll(function);
    }

    @Override
    public Object putIfAbsent(Object key, Object value) {
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return delegate.remove(key, value);
    }

    @Override
    public boolean replace(Object key, Object oldValue, Object newValue) {
        return delegate.replace(key, oldValue, newValue);
    }

    @Override
    public Object replace(Object key, Object value) {
        return delegate.replace(key, value);
    }

    @Override
    public Object computeIfAbsent(Object key, Function<? super Object, ?> mappingFunction) {
        return delegate.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public Object computeIfPresent(Object key, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        return delegate.computeIfPresent(key, remappingFunction);
    }

    @Override
    public Object compute(Object key, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        return delegate.compute(key, remappingFunction);
    }

    @Override
    public Object merge(Object key, Object value, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        return delegate.merge(key, value, remappingFunction);
    }

    @Override
    public Object clone() {
        throw new UnsupportedOperationException();
    }

    private static class CommentSuppressingWriter extends BufferedWriter {

        private boolean skipNextNewLine;

        private CommentSuppressingWriter(Writer out) {
            super(out);
        }

        @Override
        public void write(String str) throws IOException {
            if (str.startsWith("#")) {
                skipNextNewLine = true;
            } else {
                super.write(str);
            }
        }

        @Override
        public void newLine() throws IOException {
            if (skipNextNewLine) {
                skipNextNewLine = false;
            } else {
                super.newLine();
            }
        }
    }
}
