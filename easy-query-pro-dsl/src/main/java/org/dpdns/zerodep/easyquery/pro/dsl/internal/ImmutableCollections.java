package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Java 8 stand-ins for the JDK 9+ {@code List.of}/{@code copyOf} family: unmodifiable, null-hostile and, for sets and maps,
 * insertion ordered. {@link #toList()} mirrors {@code Stream.toList()}, which still accepts nulls.
 */
public final class ImmutableCollections {
    private ImmutableCollections() { }

    @SafeVarargs
    public static <E> List<E> listOf(E... elements) {
        return listCopyOf(Arrays.asList(elements));
    }

    public static <E> List<E> listCopyOf(Collection<? extends E> source) {
        List<E> copy = new ArrayList<>(source.size());
        for (E element : source) copy.add(Objects.requireNonNull(element));
        return Collections.unmodifiableList(copy);
    }

    @SafeVarargs
    public static <E> Set<E> setOf(E... elements) {
        Set<E> set = new LinkedHashSet<>();
        for (E element : elements) {
            if (!set.add(Objects.requireNonNull(element))) throw new IllegalArgumentException("Duplicate element: " + element);
        }
        return Collections.unmodifiableSet(set);
    }

    public static <E> Set<E> setCopyOf(Collection<? extends E> source) {
        Set<E> set = new LinkedHashSet<>();
        for (E element : source) set.add(Objects.requireNonNull(element));
        return Collections.unmodifiableSet(set);
    }

    public static <K, V> Map<K, V> mapCopyOf(Map<? extends K, ? extends V> source) {
        Map<K, V> map = new LinkedHashMap<>();
        source.forEach((key, value) -> map.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(map);
    }

    public static <T> Collector<T, ?, List<T>> toList() {
        return Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList);
    }

    /** Replaces multi-entry {@code Map.of}: rejects null keys/values and duplicate keys. */
    public static <K, V> MapBuilder<K, V> mapBuilder() {
        return new MapBuilder<>();
    }

    public static final class MapBuilder<K, V> {
        private final Map<K, V> entries = new LinkedHashMap<>();

        private MapBuilder() { }

        public MapBuilder<K, V> put(K key, V value) {
            if (entries.putIfAbsent(Objects.requireNonNull(key), Objects.requireNonNull(value)) != null) {
                throw new IllegalArgumentException("Duplicate key: " + key);
            }
            return this;
        }

        public Map<K, V> build() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }
}
