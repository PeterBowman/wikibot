package com.github.wikibot.dumps;

import java.util.Arrays;
import java.util.function.LongUnaryOperator;

public class EnumStats<E extends Enum<E>> {
    private final E[] constants;
    private final long[] storage;

    EnumStats(Class<E> clazz) {
        constants = clazz.getEnumConstants();
        storage = new long[constants.length];
    }

    public long get(E e) {
        return storage[e.ordinal()];
    }

    public void increment(E e) {
        ++storage[e.ordinal()];
    }

    public void add(E e, long value) {
        storage[e.ordinal()] += value;
    }

    public void transform(E e, LongUnaryOperator operator) {
        storage[e.ordinal()] = operator.applyAsLong(storage[e.ordinal()]);
    }

    public void clear() {
        Arrays.fill(storage, 0);
    }

    public void combine(EnumStats<E> other) {
        for (int i = 0; i < storage.length; i++) {
            storage[i] += other.storage[i];
        }
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append('[');

        for (int i = 0; i < constants.length; i++) {
            sb.append(constants[i]);
            sb.append('=');
            sb.append(storage[i]);

            if (i < constants.length - 1) {
                sb.append(", ");
            }
        }

        sb.append(']');
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(storage);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof EnumStats<?> other) {
            return Arrays.equals(storage, other.storage);
        } else {
            return false;
        }
    }
}
