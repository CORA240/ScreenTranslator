package com.screentranslator.app;

/**
 * Cálculos de imagen sin clases de Android (se pueden probar fuera del teléfono):
 *  - cuánto se desplazó la pantalla entre dos capturas (para que las traducciones sigan al scroll)
 *  - el tamaño del globo blanco alrededor de un texto (para cubrir el globo completo).
 */
public final class Motion {

    private Motion() {}

    public static final int BANDS = 4;

    /**
     * Perfil por filas: brillo promedio de cada fila en 4 franjas verticales.
     * @param gray brillo 0..255 de una imagen reducida (ancho w, alto h)
     */
    public static int[] rowProfile(int[] gray, int w, int h) {
        int[] p = new int[h * BANDS];
        int bandW = Math.max(1, w / BANDS);
        for (int y = 0; y < h; y++) {
            for (int b = 0; b < BANDS; b++) {
                int sum = 0, n = 0;
                for (int x = b * bandW; x < Math.min(w, (b + 1) * bandW); x++) {
                    sum += gray[y * w + x];
                    n++;
                }
                p[y * BANDS + b] = n == 0 ? 0 : sum / n;
            }
        }
        return p;
    }

    /**
     * Desplazamiento vertical entre dos perfiles, en filas del perfil.
     * Positivo = el contenido subió (el usuario bajó en la página).
     * Devuelve Integer.MIN_VALUE si el cambio no se explica por un desplazamiento
     * (cambio de página, animación) o si no hubo movimiento.
     */
    public static int verticalShift(int[] prev, int[] cur) {
        if (prev == null || cur == null || prev.length != cur.length) return Integer.MIN_VALUE;
        int h = cur.length / BANDS;
        int maxShift = h * 6 / 10;
        int minOverlap = h * 4 / 10;
        double err0 = rowError(prev, cur, h, 0, minOverlap);
        if (err0 < 1.5) return Integer.MIN_VALUE;                // nada se movió

        int best = 0;
        double bestErr = Double.MAX_VALUE;
        for (int s = -maxShift; s <= maxShift; s++) {
            double e = rowError(prev, cur, h, s, minOverlap);
            if (e < bestErr) { bestErr = e; best = s; }
        }
        // Aceptar solo si el desplazamiento explica bien el cambio.
        if (best == 0 || bestErr > 6.0 || bestErr > err0 * 0.5) return Integer.MIN_VALUE;
        return best;
    }

    /** Error promedio comparando cur[y] con prev[y + s]. */
    private static double rowError(int[] prev, int[] cur, int h, int s, int minOverlap) {
        int y0 = Math.max(0, -s), y1 = Math.min(h, h - s);
        if (y1 - y0 < minOverlap) return Double.MAX_VALUE;
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            int a = y * BANDS, b = (y + s) * BANDS;
            for (int k = 0; k < BANDS; k++) {
                sum += Math.abs(cur[a + k] - prev[b + k]);
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE : (double) sum / n;
    }

    /** Acceso al brillo de un píxel (0..255). */
    public interface Lum { int at(int x, int y); }

    /**
     * Agranda el rectángulo del texto mientras alrededor siga siendo blanco (interior del globo).
     * @param box {left, top, right, bottom} del texto; se modifica con el globo encontrado.
     * @return true si se encontró un globo claro alrededor del texto.
     */
    public static boolean expandBalloon(Lum lum, int w, int h, int[] box, int maxGrowX, int maxGrowY) {
        int l = box[0], t = box[1], r = box[2], b = box[3];
        int gl = 0, gr = 0, gt = 0, gb = 0;
        while (gl < maxGrowX && l - gl - 1 >= 0 && brightCol(lum, l - gl - 1, t, b)) gl++;
        while (gr < maxGrowX && r + gr + 1 < w && brightCol(lum, r + gr + 1, t, b)) gr++;
        while (gt < maxGrowY && t - gt - 1 >= 0 && brightRow(lum, t - gt - 1, l, r)) gt++;
        while (gb < maxGrowY && b + gb + 1 < h && brightRow(lum, b + gb + 1, l, r)) gb++;

        int minGrow = Math.max(3, (b - t) / 12);
        int sides = (gl >= minGrow ? 1 : 0) + (gr >= minGrow ? 1 : 0)
                + (gt >= minGrow ? 1 : 0) + (gb >= minGrow ? 1 : 0);
        if (sides < 3) return false;

        // Dejar un pequeño margen para no tapar el borde del globo.
        int m = 3;
        box[0] = l - Math.max(0, gl - m);
        box[2] = r + Math.max(0, gr - m);
        box[1] = t - Math.max(0, gt - m);
        box[3] = b + Math.max(0, gb - m);
        return true;
    }

    private static boolean brightCol(Lum lum, int x, int t, int b) {
        int n = 0, ok = 0;
        for (int y = t; y <= b; y += 2) { n++; if (lum.at(x, y) > 205) ok++; }
        return n > 0 && ok >= n * 0.92f;
    }

    private static boolean brightRow(Lum lum, int y, int l, int r) {
        int n = 0, ok = 0;
        for (int x = l; x <= r; x += 2) { n++; if (lum.at(x, y) > 205) ok++; }
        return n > 0 && ok >= n * 0.92f;
    }
}
