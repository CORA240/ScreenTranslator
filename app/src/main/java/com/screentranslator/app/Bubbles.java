package com.screentranslator.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reconstruye los globos de diálogo a partir de las líneas que detecta el OCR.
 *
 * El OCR a veces parte un globo en varios bloques o junta dos globos vecinos en uno.
 * En un globo de manga las líneas están una debajo de otra, muy juntas, centradas y con
 * letra del mismo tamaño; con esas reglas se agrupan las líneas, globo por globo.
 * Java puro (sin clases de Android) para poder probarlo fuera del teléfono.
 */
public final class Bubbles {

    public static final class Line {
        public final int left, top, right, bottom;
        public final String text;
        public Line(int left, int top, int right, int bottom, String text) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom; this.text = text;
        }
        int h() { return Math.max(1, bottom - top); }
        int w() { return Math.max(1, right - left); }
        float cx() { return (left + right) / 2f; }
    }

    public static final class Bubble {
        public int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right, bottom;
        public String text;
        public float lineHeight;   // alto promedio de línea (tamaño de letra)
        public int lineCount;
        /** Onomatopeya: una sola palabra con letra mucho más grande que los diálogos. */
        public boolean soundEffect;
    }

    private Bubbles() {}

    public static List<Bubble> group(List<Line> lines) {
        int n = lines.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sameBubble(lines.get(i), lines.get(j))) union(parent, i, j);
            }
        }

        List<List<Line>> groups = new ArrayList<>();
        int[] groupOf = new int[n];
        java.util.Arrays.fill(groupOf, -1);
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            if (groupOf[root] < 0) {
                groupOf[root] = groups.size();
                groups.add(new ArrayList<>());
            }
            groups.get(groupOf[root]).add(lines.get(i));
        }

        List<Bubble> out = new ArrayList<>();
        for (List<Line> g : groups) {
            Collections.sort(g, (a, b) -> a.top != b.top ? Integer.compare(a.top, b.top)
                    : Integer.compare(a.left, b.left));
            Bubble b = new Bubble();
            StringBuilder sb = new StringBuilder();
            for (Line l : g) {
                b.left = Math.min(b.left, l.left);
                b.top = Math.min(b.top, l.top);
                b.right = Math.max(b.right, l.right);
                b.bottom = Math.max(b.bottom, l.bottom);
                String t = l.text.trim();
                if (t.isEmpty()) continue;
                int len = sb.length();
                // Palabra cortada al final de línea: "GO-" + "ING" → "GOING"
                if (len >= 2 && sb.charAt(len - 1) == '-' && Character.isLetter(sb.charAt(len - 2))
                        && Character.isLetter(t.charAt(0))) {
                    sb.setLength(len - 1);
                } else if (len > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
            b.text = sb.toString();
            float hs = 0;
            for (Line l : g) hs += l.h();
            b.lineCount = g.size();
            b.lineHeight = hs / g.size();
            out.add(b);
        }
        // Tamaño de letra típico de los diálogos = mediana de las líneas.
        if (!lines.isEmpty()) {
            List<Integer> hs = new ArrayList<>();
            for (Line l : lines) hs.add(l.h());
            Collections.sort(hs);
            float median = hs.get(hs.size() / 2);
            for (Bubble b : out) {
                boolean oneWord = b.text.trim().indexOf(' ') < 0;
                b.soundEffect = oneWord && b.lineCount == 1 && b.lineHeight > median * 1.8f;
            }
        }
        // Orden de lectura aproximado: de arriba hacia abajo.
        Collections.sort(out, (a, b) -> Integer.compare(a.top, b.top));
        return out;
    }

    static boolean sameBubble(Line a, Line b) {
        float hMin = Math.min(a.h(), b.h()), hMax = Math.max(a.h(), b.h());
        if (hMax > hMin * 1.7f) return false;                       // tamaño de letra distinto
        float avgH = (a.h() + b.h()) / 2f;

        int vGap = Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom);
        if (vGap > avgH * 0.9f) return false;                       // demasiado separadas en vertical

        int hOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        if (vGap < -avgH * 0.5f) {
            // Están a la misma altura: solo unir si casi se tocan (misma línea partida).
            return hOverlap > -avgH * 0.8f;
        }
        // Una debajo de otra: deben estar centradas o superpuestas horizontalmente.
        float centerDist = Math.abs(a.cx() - b.cx());
        return centerDist < Math.max(a.w(), b.w()) * 0.45f
                || hOverlap > Math.min(a.w(), b.w()) * 0.6f;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }
}
