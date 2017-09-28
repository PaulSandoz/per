/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package per;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class PMap<K, V> {
    static final Object SUB_LAYER_NODE = new Object();

    static final Object COLLISION_NODE = new Object();

    static final int PREFIX_BIT_MASK = 31;

    static final int PREFIX_BIT_SIZE = 5;

    static final Object[] EMPTY_NODES = new Object[0];

    static final PMap<?, ?> EMPTY_PMAP = new PMap<>();

    // @@@ This may only needed for the root, break out to PMap and Node?
    //     although this value is useful for a SIZED & SUBSIZED spliterator
    // @Stable
    final int size;
    // bit map of symbols
    // @Stable
    final int bitmap;
    // [..., k, v, ....] or
    // [..., SUB_LAYER_NODE, PMap, ...] or
    // [..., COLLISION_NODE, CollisionNode, ...] or
    // invariant: a sub-layer will not consist of a single mapping node
    // @@@ mapping nodes, and sub-layer nodes could be rearranged to be
    // contiguous, with mapping nodes first, and sub-layer nodes last and
    // reversed. This simplifies traversal, but requires two bit sets.
    // @Stable
    final Object[] nodes;

    private PMap() { // empty
        size = 0;
        bitmap = 0;
        nodes = EMPTY_NODES;
    }

    private PMap(K k, V v, int levelShift) {
        this(1, 1 << symbolAtDepth(hash(k), levelShift), new Object[]{k, v});
    }

    private PMap(CollisionNode c, int levelShift) {
        this(c.ms.length / 2, 1 << symbolAtDepth(c.h, levelShift), new Object[]{COLLISION_NODE, c});
    }

    PMap(int size, int bitmap, Object[] nodes) {
        this.size = size;
        this.bitmap = bitmap;
        this.nodes = nodes;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> PMap<K, V> empty() {
        return (PMap<K, V>) EMPTY_PMAP;
    }

    public static <K, V> PMap<K, V> of(Consumer<PMapBuilder<K, V>> c) {
        PMapBuilder<K, V> b = new PMapBuilder<>();
        PMap<K, V> m = null;
        try {
            c.accept(b);
            m = b.build();
        } finally {
            if (m == null) {
                // Exception occurred
                b.clear();
            }
        }
        return m;
    }

    public static <K, V> PMap<K, V> of(K k, V v) {
        return new PMap<>(k, v, 0);
    }

    static int symbolAtDepth(int h, int dShift) { // bit string prefix at depth
        return (h >>> dShift) & PREFIX_BIT_MASK;
    }

    static int bitmapGet(int bitmap, int symbol) {
        return bitmap & (1 << symbol);
    }

    static int bitmapSet(int bitmap, int symbol) {
        return bitmap | ~(1 << symbol);
    }

    static int bitmapClear(int bitmap, int symbol) {
        return bitmap & ~(1 << symbol);
    }

    static int bitmapCountFrom(int bitmap, int symbol) {
        return Integer.bitCount(bitmap & ((1 << symbol) - 1));
    }

    static int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        for (int i = 0; i < nodes.length; i += 2) {
            @SuppressWarnings("unchecked")
            K k = (K) nodes[i];
            if (k == SUB_LAYER_NODE) {
                // Sub-layer node

                @SuppressWarnings("unchecked")
                PMap<K, V> s = (PMap<K, V>) nodes[i + 1];
                s.forEach(action);
            }
            else if (k == COLLISION_NODE) {
                // Collision node

                CollisionNode c = (CollisionNode) nodes[i + 1];
                c.forEach(action);
            }
            else {
                // Mapping node

                @SuppressWarnings("unchecked")
                V v = (V) nodes[i + 1];
                action.accept(k, v);
            }
        }
    }

    public Optional<V> get(K k) {
        return get(k, hash(k), 0);
    }

    private Optional<V> get(K k, int h, int dShift) {
        int symbol = symbolAtDepth(h, dShift);
        if (bitmapGet(bitmap, symbol) == 0) {
            // Mapping does not exist
            return Optional.empty();
        }

        int nodeCount = bitmapCountFrom(bitmap, symbol);
        Object _k = nodes[nodeCount * 2];
        if (_k == SUB_LAYER_NODE) {
            // Sub-layer node

            @SuppressWarnings("unchecked")
            PMap<K, V> s = (PMap<K, V>) nodes[nodeCount * 2 + 1];
            return s.get(k, h, dShift + PREFIX_BIT_SIZE);
        }
        else if (k == COLLISION_NODE) {
            // Collision node

            CollisionNode c = (CollisionNode) nodes[nodeCount * 2 + 1];
            return c.get(k, h);
        }
        else {
            // Mapping node
            // @@@ can compare hash codes first if cached

            if (_k.equals(k)) {
                // Mapping exists

                @SuppressWarnings("unchecked")
                V _v = (V) nodes[nodeCount * 2 + 1];
                return Optional.of(_v);
            }

            return Optional.empty();
        }
    }

    public PMap<K, V> put(K k, V v) {
        return put(k, v, hash(k), 0);
    }

    private PMap<K, V> put(K k, V v, int h, int dShift) {
        int symbol = symbolAtDepth(h, dShift);

        int bit = bitmapGet(bitmap, symbol);

        if (bit == 0) {
            // Mapping node is free

            int levelCount = Integer.bitCount(bitmap);

            // Create a new node array with an increased size of one
            Object[] n_nodes = Arrays.copyOf(nodes, levelCount * 2 + 2);

            // Count the number of nodes to the left of the new mapping node
            int nodeCount = bitmapCountFrom(bitmap, symbol);
            // Expand for the new mapping node
            System.arraycopy(n_nodes, nodeCount * 2,
                             n_nodes, nodeCount * 2 + 2, (levelCount - nodeCount) * 2);

            // Set the bit of the mapping node
            int n_bitmap = bitmap | (1 << symbol);

            // Set the mapping node
            n_nodes[nodeCount * 2] = k;
            n_nodes[nodeCount * 2 + 1] = v;

            // Return a new map at the same level
            return new PMap<>(size + 1, n_bitmap, n_nodes);
        }

        int nodeCount = bitmapCountFrom(bitmap, symbol);
        Object _k = nodes[nodeCount * 2];
        if (_k == SUB_LAYER_NODE) {
            // Sub-layer node

            @SuppressWarnings("unchecked")
            PMap<K, V> s = (PMap<K, V>) nodes[nodeCount * 2 + 1];

            Object[] n_nodes = nodes.clone();
            n_nodes[nodeCount * 2 + 1] = s.put(k, v, h, dShift + PREFIX_BIT_SIZE);

            return new PMap<>(size + 1, bitmap, n_nodes);
        }
        else if (_k == COLLISION_NODE) {
            // Collision node

            CollisionNode c = (CollisionNode) nodes[nodeCount * 2 + 1];

            Object[] n_nodes = nodes.clone();
            if (h == c.h) {
                // Collision with existing keys in collision node
                n_nodes[nodeCount * 2 + 1] = c.add(new Object[]{k, v});
            }
            else {
                // Replace collision node with a sub-layer node
                n_nodes[nodeCount * 2] = SUB_LAYER_NODE;
                n_nodes[nodeCount * 2 + 1] = new PMap<>(c, dShift + PREFIX_BIT_SIZE).
                        put(k, v, h, dShift + PREFIX_BIT_SIZE);
            }
            return new PMap<>(size + 1, bitmap, n_nodes);
        }
        else {
            // Prefix conflict with existing mapping node
            // @@@ can compare hash codes first if cached

            if (_k.equals(k)) {
                // Replace value

                Object[] n_nodes = nodes.clone();
                n_nodes[nodeCount * 2 + 1] = v;
                return new PMap<>(size + 1, bitmap, n_nodes);
            }

            Object[] n_nodes = nodes.clone();
            Object _v = n_nodes[nodeCount * 2 + 1];
            if (h == hash(_k)) {
                // Replace mapping node with collision node
                n_nodes[nodeCount * 2] = COLLISION_NODE;
                n_nodes[nodeCount * 2 + 1] = new CollisionNode(h, new Object[]{_k, _v, k, v});
            }
            else {
                // Replace mapping node with a sub-layer node
                n_nodes[nodeCount * 2] = SUB_LAYER_NODE;
                n_nodes[nodeCount * 2 + 1] = new PMap<>(_k, _v, dShift + PREFIX_BIT_SIZE).
                        put(k, v, h, dShift + PREFIX_BIT_SIZE);
            }

            return new PMap<>(size + 1, bitmap, n_nodes);
        }
    }

    public PMap<K, V> remove(K k) {
        return remove(k, hash(k), 0);
    }

    private PMap<K, V> remove(K k, int h, int dShift) {
        int symbol = symbolAtDepth(h, dShift);

        int bit = bitmapGet(bitmap, symbol);

        if (bit == 0) {
            // Mapping does not exist

            return this;
        }

        int nodeCount = bitmapCountFrom(bitmap, symbol);
        @SuppressWarnings("unchecked")
        K _k = (K) nodes[nodeCount * 2];
        if (_k == SUB_LAYER_NODE) {
            // Sub-layer node

            @SuppressWarnings("unchecked")
            PMap<K, V> s = (PMap<K, V>) nodes[nodeCount * 2 + 1];
            PMap<K, V> r = s.remove(k, h, dShift + PREFIX_BIT_SIZE);

            if (r == s) {
                // No mapping exists

                return this;
            }

            int r_bitmap = r.bitmap;
            if ((r_bitmap & (r_bitmap - 1)) == 0) {
                // One mapping node remaining in child, fold into parent to
                // retain mapping node count invariant
                // @@@ How to fold this into the parent without allocation?

                Object[] n_nodes = nodes.clone();
                Object[] r_nodes = r.nodes;
                n_nodes[nodeCount * 2] = r_nodes[0];
                n_nodes[nodeCount * 2 + 1] = r_nodes[1];
                return new PMap<>(size - 1, bitmap, n_nodes);
            }

            Object[] n_nodes = nodes.clone();
            n_nodes[nodeCount * 2 + 1] = r;
            return new PMap<>(size - 1, bitmap, n_nodes);
        }
        else if (_k == COLLISION_NODE) {
            // Collision node

            CollisionNode c = (CollisionNode) nodes[nodeCount * 2 + 1];

            if (h != c.h) {
                // Mapping does not exist

                return this;
            }

            CollisionNode r = c.remove(k);
            if (r == c) {
                // Mapping does not exist

                return this;
            }

            Object[] n_nodes = nodes.clone();
            if (r.ms.length == 2) {
                // Fold into node array to retain collision node count invariant
                // @@@ How to fold this into the parent without allocation?
                n_nodes[nodeCount * 2] = r.ms[0];
                n_nodes[nodeCount * 2 + 1] = r.ms[1];
            }
            else {
                n_nodes[nodeCount * 2 + 1] = r;
            }
            return new PMap<>(size - 1, bitmap, n_nodes);
        }
        else {
            // Prefix conflict with existing mapping node
            // @@@ can compare hash codes first if cached

            if (!_k.equals(k)) {
                // Mapping does not exist

                return this;
            }

            // Remove mapping

            int levelCount = Integer.bitCount(bitmap);

            if (levelCount == 1) {
                // Empty layer

                return empty();
            }

            Object[] n_nodes = new Object[levelCount * 2 - 2];
            // Shrink for the removed mapping node
            // Copy nodes before removed node
            System.arraycopy(nodes, 0,
                             n_nodes, 0, nodeCount * 2);
            // Copy nodes after removed node
            System.arraycopy(nodes, nodeCount * 2 + 2,
                             n_nodes, nodeCount * 2, (levelCount - nodeCount - 1) * 2);
            int n_bitmap = bitmapClear(bitmap, symbol);
            return new PMap<>(size - 1, n_bitmap, n_nodes);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[ ");
        int l = sb.length();
        forEach((k, v) -> {
            if (sb.length() > l) sb.append(", ");
            sb.append(k).append(" -> ").append(v);
        });
        return sb.append(" ]").toString();
    }

    static class CollisionNode {
        final int h;
        final Object[] ms;
        // invariant: ms.length >= 4, at least two mappings

        CollisionNode(int h, Object[] ms) {
            this.h = h;
            this.ms = ms;
        }

        CollisionNode(CollisionNode that, Object[] vs) {
            this.h = that.h;
            this.ms = Arrays.copyOf(that.ms, that.ms.length + vs.length);
            System.arraycopy(vs, 0,
                             ms, that.ms.length, vs.length);
        }

        CollisionNode add(Object[] vs) {
            return new CollisionNode(this, vs);
        }

        @SuppressWarnings("unchecked")
        <K, V> void forEach(BiConsumer<? super K, ? super V> action) {
            for (int i = 0; i < ms.length; i += 2) {
                action.accept((K) ms[i], (V) ms[i + 1]);
            }
        }

        @SuppressWarnings("unchecked")
        <K, V> Optional<V> get(K k, int h) {
            if (h != this.h)
                return Optional.empty();

            for (int i = 0; i < ms.length; i += 2) {
                K _k = (K) ms[i];
                if (k.equals(_k))
                    return Optional.of((V) ms[i + 1]);
            }

            return Optional.empty();
        }

        <K> CollisionNode remove(K k) {
            for (int i = 0; i < ms.length; i += 2) {
                if (k.equals(ms[i])) {
                    Object[] n_ms = new Object[ms.length - 2];
                    System.arraycopy(ms, 0,
                                     n_ms, 0, i);
                    System.arraycopy(ms, i + 2,
                                     n_ms, i, n_ms.length - i);
                    return new CollisionNode(h, n_ms);
                }
            }
            return this;
        }
    }
}
