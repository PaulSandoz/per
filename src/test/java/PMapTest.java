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
import org.junit.Test;
import per.PMap;
import per.Visualizer;

public class PMapTest {

    @Test
    public void primitives() {
        PMap<String, Class<?>> m = PMap.<String, Class<?>>empty()
                .put("B", Byte.class)
                .put("D", Double.class)
                .put("F", Float.class)
                .put("I", Integer.class)
                .put("J", Long.class)
                .put("L", Object.class)
                .put("S", Short.class)
                .put("V", Void.class)
                .put("Z", Boolean.class);

        Visualizer.visualize(m);
    }

    @Test
    public void fruit() {
        PMap<String, String> m = PMap.<String, String>empty()
                .put("banana", "yellow")
                .put("orange", "orange")
                .put("lemon", "yellow")
                .put("apple", "green")
                .put("grapefruit", "yellow")
                .put("blackberry", "black")
                .put("rasberry", "red")
                .put("blueberry", "blue")
                .put("gooseberry", "green")
                .put("cranberry", "red")
                .put("mango", "yellow")
                .put("acai", "purple")
                .put("plum", "purple");

        Visualizer.visualize(m, m.remove("blueberry"));
    }

    @Test
    public void collision() {
        PMap<Object, Object> b = PMap.empty()
                .put(new IntKey(1, 0), 1)
                .put(new IntKey(2, 0), 2)
                .put(32, 32);

        Visualizer.visualize(b,
                             b.remove(new IntKey(2, 0)));
    }

    @Test
    public void degenerate() {
        // Hash is h ^ (h >>> 16)
        PMap<Object, Object> m = PMap.empty()
                .put(0b00_00000_0__0000_0000__0_00000_00__000_00000, "00")
                .put(0b10_00000_0__0000_0000__1_00000_00__000_00000, "10")
                .put(0b01_00000_0__0000_0000__0_10000_00__000_00000, "01")
                .put(0b11_00000_0__0000_0000__1_10000_00__000_00000, "11");
        Visualizer.visualize(m);
    }

    static final class IntKey {
        final int i;
        final int h;

        public IntKey(int i, int h) {
            this.i = i;
            this.h = h;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IntKey))
                return false;
            IntKey key = (IntKey) o;
            return i == key.i;
        }

        @Override
        public int hashCode() {
            return h;
        }

        @Override
        public String toString() {
            return Integer.toString(i);
        }

    }
}
