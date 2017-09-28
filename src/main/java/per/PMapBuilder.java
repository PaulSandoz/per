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

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Arrays;

// A builder of a PMap.
// Building is thread confined.
// Freezing transitions from mutable to immutable state and invalidates
// the builder.
public class PMapBuilder<K, V> {
    static Unsafe U = getUnsafe();

    final Thread t;
    MutableHAMT<K, V> m;

    PMapBuilder() {
        t = Thread.currentThread();
        m = new MutableHAMT<>();
    }

    static Unsafe getUnsafe() {
        try {
            Field uf = Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            return (sun.misc.Unsafe) uf.get(null);
        }
        catch (Exception e) {
            throw new InternalError(e);
        }
    }

    // This method uses Unsafe to monkey patch the class header
    // of MutableHAMT to PMap.  In effect "freezing" the nodes.  This
    // works because MutableHAMT and PMap have the same field layout.
    static <K, V> PMap<K, V> toPMap(MutableHAMT<K, V> m) {
        for (int i = 0; i < m.nodes.length; i += 2) {
            @SuppressWarnings("unchecked")
            K k = (K) m.nodes[i];
            if (k == PMap.SUB_LAYER_NODE) {
                // Sub-layer node

                @SuppressWarnings("unchecked")
                MutableHAMT<K, V> sm = (MutableHAMT<K, V>) m.nodes[i + 1];
                m.nodes[i + 1] = toPMap(sm);
            }
            else if (k == PMap.COLLISION_NODE) {
                throw new UnsupportedOperationException("Collisions not supported");
            }
        }

        // Monkey patch the header of m to freeze it
        Object o = m;
        m = null;
        // @@@ This should be a method on Unsafe
        //     With a corresponding method that validates the two classes
        //     are compatible with respect to the field layout
        int classHeader_PMap = U.getInt(PMap.EMPTY_PMAP, 8L);
        U.putOrderedInt(o, 8L, classHeader_PMap);

        @SuppressWarnings("unchecked")
        PMap<K, V> pm = (PMap<K, V>) o;
        return pm;
    }

    public PMapBuilder<K, V> put(K k, V v) {
        // Guard a put, only if the builder has not been built and
        // the current thread is the same as the thread that created
        // the builder
        if (m == null || t != Thread.currentThread())
            throw new IllegalStateException();

        m.put(k, v, PMap.hash(k), 0);
        return this;
    }

    PMap<K, V> build() {
        MutableHAMT<K, V> _m = m;
        // Transition the builder to the built state
        clear();
        return toPMap(_m);
    }

    void clear() {
        m = null;
    }

    static final class MutableHAMT<K, V> {
        int size;
        int bitmap;
        Object[] nodes;

        MutableHAMT() {
            size = 0;
            bitmap = 0;
            nodes = PMap.EMPTY_NODES;
        }

        MutableHAMT(K k, V v, int levelShift) {
            this(1, 1 << PMap.symbolAtDepth(PMap.hash(k), levelShift), new Object[]{k, v});
        }

        MutableHAMT(int size, int bitmap, Object[] nodes) {
            this.size = size;
            this.bitmap = bitmap;
            this.nodes = nodes;
        }

        void put(K k, V v, int h, int dShift) {
            int symbol = PMap.symbolAtDepth(h, dShift);

            int bit = PMap.bitmapGet(bitmap, symbol);

            if (bit == 0) {
                // Mapping node is free

                int levelCount = Integer.bitCount(bitmap);

                // Create a new node array with an increased size of one
                Object[] n_nodes = Arrays.copyOf(nodes, levelCount * 2 + 2);

                // Count the number of nodes to the left of the new mapping node
                int nodeCount = PMap.bitmapCountFrom(bitmap, symbol);
                // Expand for the new mapping node
                System.arraycopy(n_nodes, nodeCount * 2,
                                 n_nodes, nodeCount * 2 + 2, (levelCount - nodeCount) * 2);

                // Set the bit of the mapping node
                int n_bitmap = bitmap | (1 << symbol);

                // Set the mapping node
                n_nodes[nodeCount * 2] = k;
                n_nodes[nodeCount * 2 + 1] = v;

                size++;
                bitmap = n_bitmap;
                nodes = n_nodes;
            }

            int nodeCount = PMap.bitmapCountFrom(bitmap, symbol);
            Object _k = nodes[nodeCount * 2];
            if (_k == PMap.SUB_LAYER_NODE) {
                // Sub-layer node

                @SuppressWarnings("unchecked")
                MutableHAMT<K, V> s = (MutableHAMT<K, V>) nodes[nodeCount * 2 + 1];
                s.put(k, v, h, dShift + PMap.PREFIX_BIT_SIZE);
            }
            else if (_k == PMap.COLLISION_NODE) {
                throw new InternalError("Should not be here");
            }
            else {
                // Prefix conflict with existing mapping node
                // @@@ can compare hash codes first if cached

                if (_k.equals(k)) {
                    // Replace value
                    nodes[nodeCount * 2 + 1] = v;
                    return;
                }

                Object _v = nodes[nodeCount * 2 + 1];
                if (h == PMap.hash(_k)) {
                    throw new UnsupportedOperationException("Collisions not supported");
                }
                else {
                    // Replace mapping node with a sub-layer node
                    nodes[nodeCount * 2] = PMap.SUB_LAYER_NODE;
                    @SuppressWarnings("unchecked")
                    MutableHAMT<K, V> subNode = new MutableHAMT<>((K) _k, (V) _v, dShift + PMap.PREFIX_BIT_SIZE);
                    nodes[nodeCount * 2 + 1] = subNode;
                    subNode.put(k, v, h, dShift + PMap.PREFIX_BIT_SIZE);
                }
            }
        }
    }

}
