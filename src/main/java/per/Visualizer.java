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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toList;

public class Visualizer {

    static void writeEdge(Node c, Node p, PrintWriter w) {
        writeEdge(p.name(), c.name(), w);
    }

    static void writeEdge(String from, String to, PrintWriter w) {
        w.format("%s -> %s\n", quote(from), quote(to));
    }

    static void writeAttributes(Map<String, String> m, PrintWriter w) {
        writeAttributes(m, " = ", w);
    }

    static void writeAttributes(Map<String, String> m, String separator, PrintWriter w) {
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first)
                w.println();
            if (e.getValue() != null)
                w.format("%s%s%s", e.getKey(), separator, e.getValue());
            else
                w.format("%s", e.getKey());
            first = false;
        }
    }

    static String writeLabels(Map<String, String> m) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        writeAttributes(m, ": ", pw);
        pw.flush();
        return sw.toString();
    }

    public static void toDot(String graphName, PrintWriter w, PMap<?, ?>... m) {
        GraphEdges ges = GraphEdges.toGraphEdges(m);
        ges.toDot(graphName, w);
        w.flush();
    }

    public static void toSVG(String graphName, PMap<?, ?>... m) throws IOException {
        toDot(graphName, new PrintWriter(new FileOutputStream(graphName + ".dot")), m);
        Dot.dotToSvg(new FileInputStream("x.dot"),
                     new FileOutputStream(graphName + ".svg"));
    }

    public static void visualize(PMap<?, ?>... m) throws IOException {
        toSVG("x", m);
        Dot.dotToSvg(new FileInputStream("x.dot"),
                     new FileOutputStream("x.svg"));
        Browser.openInBrowser("x.svg");
    }

    static String quote(String s) {
        return "\"" + s + "\"";
    }

    static String prefixString(int p) {
        return prefixString(p, 7);
    }

    static String prefixString(int p, int d) {
        return toBinaryString(p, Math.min((d * 5), 32), 5);
    }

    static String toBinaryString(int v) {
        return toBinaryString(v, 32, 8);
    }

    static String toBinaryString(int v, int bits, int uBit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits; i++) {
            if (i > 0 && i % uBit == 0)
                sb.append('_');
            sb.append((v & (1 << i)) > 0 ? '1' : '0');
        }
        return sb.toString();
    }

    static class GraphEdges {
        // Edges, child -> parent
        Map<Node, Node> edges = new HashMap<>();

        static GraphEdges toGraphEdges(PMap<?, ?>... ms) {
            GraphEdges t = new GraphEdges();

            for (PMap<?, ?> m : ms) {
                MapNode root = new MapNode(0, 0, m);
                t.traverse(root);
            }
            return t;
        }

        void addEdge(Node child, Node parent) {
            edges.put(child, parent);
        }

        MapNode findRoot() {
            return (MapNode) edges.values().stream().
                    filter(mn -> mn.d == 0).
                    findFirst().get();
        }

        void toDot(String graphName, PrintWriter w) {
            w.println(String.format("digraph %s {", quote(graphName)));

            // Root node
            MapNode root = findRoot();

            // Write root nodes
            edges.values().stream()
                    .filter(mn -> mn.d == 0)
                    .forEach(n -> n.writeNode(w));

            // Order non-root nodes
            List<Node> children = edges.keySet().stream()
                    .sorted(Comparator.comparingInt(a -> a.p))
                    .collect(toList());

            // Write nodes
            Set<String> dups = new HashSet<>();
            children.forEach(c -> {
                if (dups.add(c.name())) {
                    c.writeNode(w);
                }
            });

            // Write edges
            dups.clear();
            children.forEach(c -> {
                Node p = edges.get(c);
                if (dups.add(c.name() + p.name())) {
                    writeEdge(c, p, w);
                }
            });

            w.println("}");
        }

        void traverse(MapNode parent) {
            PMap<?, ?> m = parent.n;

            ByteBuffer bb = ByteBuffer.allocate(4);
            bb.order(ByteOrder.nativeOrder());
            BitSet b = BitSet.valueOf(bb.putInt(0, m.bitmap));

            int h = -1;
            int d = parent.d + 1;
            for (int i = 0; i < m.nodes.length; i += 2) {
                h = b.nextSetBit(h + 1);
                int p = (h << (5 * parent.d)) | parent.p;

                Node c;
                Object k = m.nodes[i];
                if (k == PMap.SUB_LAYER_NODE) {
                    // Sub-layer node
                    MapNode sn = new MapNode(d, p, m.nodes[i + 1]);
                    c = sn;
                    traverse(sn);
                }
                else if (k == PMap.COLLISION_NODE) {
                    // Collision node
                    String n = parent.name() + ".collision." + i;
                    c = new CollisionNode(n, d, p, m.nodes[i + 1]);
                    Object[] entries = ((PMap.CollisionNode) m.nodes[i + 1]).ms;

                    for (int e = 0; e < entries.length; e += 2) {
                        NamedNode kn = new NamedNode(n + ".key." + e, d, entries[e], "box");
                        addEdge(kn, c);
                        NamedNode vn = new NamedNode(n + ".value." + e, d, entries[e + 1]);
                        addEdge(vn, kn);

                    }
                }
                else {
                    // Mapping node
                    String n = parent.name() + ".key." + i;
                    c = new KeyNode(n, d, p, k);
                    NamedNode vn = new NamedNode(n + ".value", d, m.nodes[i + 1]);
                    addEdge(vn, c);
                }
                addEdge(c, parent);
            }
        }
    }

    static abstract class Node {
        final String name;
        final int d;
        final int p;

        public Node(String name, int d, int p) {
            this.name = name;
            this.d = d;
            this.p = p;
        }

        String name() {
            return name;
        }

        void writeNode(PrintWriter w) {
            w.print(quote(name()));
            w.println(" [");

            writeAttributes(attributes(), w);

            w.println("];");
        }

        abstract Map<String, String> attributes();
    }

    static class MapNode extends Node {
        PMap<?, ?> n;

        MapNode(int d, int p, Object n) {
            super(Integer.toString(System.identityHashCode(n)), d, p);
            this.n = (PMap<?, ?>) n;
        }

        Map<String, String> attributes() {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("shape", "box");
            attrs.put("style", "\"rounded, filled\"");
            Map<String, String> label = new LinkedHashMap<>();
            if (d > 0) {
                label.put("prefix", prefixString(p, d));
            }
            label.put("bitmap", toBinaryString(n.bitmap));
            label.put("size", Integer.toString(n.nodes.length / 2));

            attrs.put("label", quote(writeLabels(label)));
            return attrs;
        }
    }

    static class CollisionNode extends Node {
        PMap.CollisionNode n;

        CollisionNode(String name, int d, int p, Object n) {
            super(name, d, p);
            this.n = (PMap.CollisionNode) n;
        }

        Map<String, String> attributes() {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("shape", "folder");
            attrs.put("style", "filled");
            attrs.put("fillcolor", "indianred2");

            Map<String, String> label = new LinkedHashMap<>();
            label.put("prefix", prefixString(p, d));
            label.put("hash", prefixString(n.h));
            label.put("size", Integer.toString(n.ms.length / 2));

            attrs.put("label", quote(writeLabels(label)));
            return attrs;
        }
    }

    static class KeyNode extends Node {
        Object n;

        KeyNode(String name, int d, int p, Object n) {
            super(name, d, p);
            this.n = n;
        }

        Map<String, String> attributes() {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("shape", "box");

            Map<String, String> label = new LinkedHashMap<>();
            label.put("prefix", prefixString(p, d));
            label.put("hash", prefixString(PMap.hash(n)));
            label.put(n.toString(), null);

            attrs.put("label", quote(writeLabels(label)));
            return attrs;
        }
    }

    static class NamedNode extends Node {
        Object n;
        String shape;

        NamedNode(String name, int d, Object n) {
            this(name, d, n, null);
        }

        NamedNode(String name, int d, Object n, String shape) {
            super(name, d, 0);
            this.n = n;
            this.shape = shape;
        }

        Map<String, String> attributes() {
            Map<String, String> attrs = new HashMap<>();
            if (shape != null)
                attrs.put("shape", shape);

            attrs.put("label", quote(n.toString()));
            return attrs;
        }
    }
}
