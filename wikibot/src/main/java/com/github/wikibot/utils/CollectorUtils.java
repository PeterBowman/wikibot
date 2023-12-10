package com.github.wikibot.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class CollectorUtils {
    private CollectorUtils() {}

    private static <K, V, M extends Map<K, V>>
    BinaryOperator<M> mapMerger(BinaryOperator<V> mergeFunction) {
        return (m1, m2) -> {
            for (var e : m2.entrySet()) {
                m1.merge(e.getKey(), e.getValue(), mergeFunction);
            }

            return m1;
        };
    }

    public static <T, K>
    Collector<T, ?, Map<K, List<T>>>
    groupingByFlattened(Function<? super T, ? extends Stream<? extends K>> classifier) {
        return groupingByFlattened(classifier, Collectors.toList());
    }

    public static <T, K, A, D>
    Collector<T, ?, Map<K, D>> groupingByFlattened(Function<? super T, ? extends Stream<? extends K>> classifier,
                                                   Collector<? super T, A, D> downstream) {
        return groupingByFlattened(classifier, HashMap::new, downstream);
    }

    public static <T, K, D, A, M extends Map<K, D>>
    Collector<T, ?, M> groupingByFlattened(Function<? super T, ? extends Stream<? extends K>> classifier,
                                           Supplier<M> mapFactory,
                                           Collector<? super T, A, D> downstream) {
        Supplier<A> downstreamSupplier = downstream.supplier();
        BiConsumer<A, ? super T> downstreamAccumulator = downstream.accumulator();

        BiConsumer<Map<K, A>, T> accumulator = (m, t) -> {
            Stream<? extends K> keyStream = Objects.requireNonNull(classifier.apply(t), "element cannot be mapped to a null key");

            keyStream.forEach(key -> {
                A container = m.computeIfAbsent(key, k -> downstreamSupplier.get());
                downstreamAccumulator.accept(container, t);
            });
        };

        BinaryOperator<Map<K, A>> merger = mapMerger(downstream.combiner());

        @SuppressWarnings("unchecked")
        Supplier<Map<K, A>> mangledFactory = (Supplier<Map<K, A>>) mapFactory;

        if (downstream.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)) {
            @SuppressWarnings("unchecked") // nonsense, but wouldn't compile otherwise, needs a dummy finisher
            Function<Map<K, A>, M> finisher = intermediate -> (M) intermediate;
            return Collector.of(mangledFactory, accumulator, merger, finisher);
        } else {
            @SuppressWarnings("unchecked")
            Function<A, A> downstreamFinisher = (Function<A, A>) downstream.finisher();

            Function<Map<K, A>, M> finisher = intermediate -> {
                intermediate.replaceAll((k, v) -> downstreamFinisher.apply(v));
                @SuppressWarnings("unchecked")
                M castResult = (M) intermediate;
                return castResult;
            };

            return Collector.of(mangledFactory, accumulator, merger, finisher);
        }
    }
}
