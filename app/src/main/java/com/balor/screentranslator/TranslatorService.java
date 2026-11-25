package com.balor.screentranslator;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Traductor en vivo sobre la pantalla (manga, juegos, cualquier app).
 *
 * Toque en la burbuja: activa/desactiva el modo en vivo.
 * Mantener presionada: abre los ajustes. Arrastrar: mueve la burbuja.
 *
 * Ciclo: captura de pantalla (~2.5 por segundo) → firma reducida de la imagen →
 * si cambió y ya se quedó quieta → OCR (ML Kit) → traducción (con caché) → etiquetas encima.
 * En Android 14+ se captura solo la ventana de la app/juego, así la capa de traducción no
 * estorba al OCR y puede quedarse visible mientras se actualiza (sin parpadeo).
 */
public class TranslatorService extends AccessibilityService {

    private static final long INTERVAL_MS = 400;        // mínimo permitido por Android: 333 ms
    private static final long FORCE_AFTER_MS = 1500;    // traducir aunque la pantalla siga animada
    private static final int GRID_W = 36;               // resolución de la firma para detectar cambios
    private static final float STABLE_T = 0.02f;
    private static final float BIG_CHANGE_T = 0.22f;    // desplazamiento / cambio de página
    private static final float TEXT_CHANGE_T = 0.12f;
    private static final int MAX_BLOCKS = 60;

    private static TranslatorService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private FrameLayout overlay;
    private final int[] overlayLoc = new int[2];

    private TextRecognizer recognizer;
    private TextTranslator translator;

    private volatile boolean live;
    private volatile int generation;
    private volatile Rect bubbleRect = new Rect();

    // ---- estado que solo toca el hilo "worker"
    private int[] ref;              // firma del cuadro que corresponde a la traducción visible
    private int[] prev;             // firma del cuadro anterior
    private boolean[] textMask;     // celdas de la firma cubiertas por etiquetas
    private int gridH;
    private long changeAt;
    private boolean overlayShown;

    public static boolean isRunning() { return instance != null; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        translator = new TextTranslator(this);
        showBubble();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        stopLive();
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        worker.shutdownNow();
        pool.shutdownNow();
        if (recognizer != null) recognizer.close();
        if (translator != null) translator.close();
        instance = null;
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // =================================================================== burbuja

    private void showBubble() {
        bubble = new TextView(this);
        bubble.setText("文A");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(dp(6));
        paintBubble();

        int size = dp(50);
        bubbleLp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleLp.x = dm.widthPixels - size - dp(8);
        bubbleLp.y = dm.heightPixels / 3;
        updateBubbleRect();

        bubble.setOnTouchListener(new BubbleTouch());
        wm.addView(bubble, bubbleLp);
    }

    private void paintBubble() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(live ? 0xE62E7D32 : 0xE61565C0); // verde = en vivo, azul = apagado
        bg.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(bg);
        bubble.setText(live ? "ON" : "文A");
    }

    private void updateBubbleRect() {
        bubbleRect = new Rect(bubbleLp.x, bubbleLp.y,
                bubbleLp.x + bubbleLp.width, bubbleLp.y + bubbleLp.height);
    }

    private class BubbleTouch implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(TranslatorService.this).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean moved, longFired;
        private final Runnable longPress = () -> {
            longFired = true;
            openSettings();
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bubbleLp.x;
                    startY = bubbleLp.y;
                    moved = false;
                    longFired = false;
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true;
                        main.removeCallbacks(longPress);
                    }
                    if (moved) {
                        bubbleLp.x = startX + (int) dx;
                        bubbleLp.y = startY + (int) dy;
                        wm.updateViewLayout(bubble, bubbleLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    updateBubbleRect();
                    if (!moved && !longFired) {
                        if (live) stopLive(); else startLive();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
            }
            return false;
        }
    }

    private void openSettings() {
        stopLive();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    // =================================================================== modo en vivo

    private void startLive() {
        live = true;
        windowModeOk = true;
        final int gen = ++generation;
        paintBubble();
        addOverlayWindow();
        worker.execute(() -> {
            resetState();
            if (Prefs.ENGINE_OFFLINE.equals(Prefs.engine(this))) {
                toast("Preparando traductor sin conexión (la primera vez descarga ~30 MB)…");
                try {
                    translator.prepareOffline();
                } catch (Exception e) {
                    toast("No se pudo preparar el modelo sin conexión: " + e.getMessage());
                }
            }
            if (gen == generation) main.post(this::tick);
        });
        if (Build.VERSION.SDK_INT < 34) {
            toast("Modo en vivo activo. En Android 14+ la actualización es más fluida.");
        }
    }

    private void stopLive() {
        live = false;
        generation++;
        main.removeCallbacks(tickRunnable);
        if (bubble != null) paintBubble();
        removeOverlayWindow();
    }

    private final Runnable tickRunnable = this::tick;

    private void scheduleNext(long delay) {
        main.removeCallbacks(tickRunnable);
        if (live) main.postDelayed(tickRunnable, delay);
    }

    /** Hilo principal: pide una captura. */
    private void tick() {
        if (!live) return;
        final int gen = generation;

        // Android 14+: capturar solo la ventana de la app (sin nuestra capa encima).
        if (Build.VERSION.SDK_INT >= 34 && windowModeOk) {
            AccessibilityWindowInfo w = findAppWindow();
            if (ownFront) { scheduleNext(1000); return; }
            if (w != null) {
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                takeScreenshotOfWindow(w.getId(), worker, new Shot(gen, true, b.left, b.top));
                return;
            }
        } else if (ownAppInFront()) {
            scheduleNext(1000);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, new Shot(gen, false, 0, 0));
    }

    private boolean ownFront;
    private volatile boolean windowModeOk = true;

    private AccessibilityWindowInfo findAppWindow() {
        AccessibilityWindowInfo best = null;
        ownFront = false;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (best == null || w.getLayer() > best.getLayer()) best = w;
            }
        } catch (Exception ignored) {}
        if (best != null) {
            AccessibilityNodeInfo root = best.getRoot();
            if (root != null && getPackageName().contentEquals(String.valueOf(root.getPackageName()))) {
                ownFront = true;
                return null;
            }
        }
        return best;
    }

    private boolean ownAppInFront() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && getPackageName().contentEquals(String.valueOf(root.getPackageName()));
    }

    private class Shot implements TakeScreenshotCallback {
        final int gen;
        final boolean clean;    // true = la captura no incluye nuestra capa
        final int offX, offY;   // posición de la captura en la pantalla

        Shot(int gen, boolean clean, int offX, int offY) {
            this.gen = gen; this.clean = clean; this.offX = offX; this.offY = offY;
        }

        @Override
        public void onSuccess(ScreenshotResult result) {   // corre en "worker"
            Bitmap bmp = null;
            try {
                HardwareBuffer hb = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                if (hw != null) {
                    bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                }
                hb.close();
                if (bmp != null && gen == generation) process(bmp, clean, offX, offY, gen);
            } catch (Exception e) {
                // Captura u OCR fallidos: se reintenta en el siguiente ciclo.
            } finally {
                if (bmp != null) bmp.recycle();
                if (gen == generation) main.post(() -> scheduleNext(INTERVAL_MS));
            }
        }

        @Override
        public void onFailure(int errorCode) {
            if (gen != generation) return;
            // Si la app no permite capturar su ventana, usar captura de pantalla completa.
            if (clean && errorCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) windowModeOk = false;
            long wait = errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? 350 : 1200;
            main.post(() -> scheduleNext(wait));
        }
    }

    // =================================================================== detección de cambios (worker)

    private void resetState() {
        ref = null;
        prev = null;
        textMask = null;
        overlayShown = false;
        changeAt = System.currentTimeMillis();
    }

    private int[] signature(Bitmap bmp) {
        gridH = Math.max(8, GRID_W * bmp.getHeight() / Math.max(1, bmp.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(bmp, GRID_W, gridH, true);
        int[] px = new int[GRID_W * gridH];
        small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, gridH);
        small.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
        }
        return px;
    }

    /** Fracción de celdas que cambiaron. mode: 0 = todas, 1 = fuera de etiquetas, 2 = bajo etiquetas. */
    private float diff(int[] a, int[] b, boolean[] mask, int mode) {
        if (a == null || b == null || a.length != b.length) return 1f;
        int changed = 0, total = 0;
        for (int i = 0; i < a.length; i++) {
            boolean m = mask != null && mask[i];
            if (mode == 1 && m) continue;
            if (mode == 2 && !m) continue;
            total++;
            if (Math.abs(a[i] - b[i]) > 18) changed++;
        }
        return total == 0 ? 0f : (float) changed / total;
    }

    private float changeThreshold() {
        // sensibilidad 1..10 → umbral 10%..1% de la pantalla
        return Math.max(0.01f, 0.11f - Prefs.sensitivity(this) * 0.01f);
    }

    private void process(Bitmap bmp, boolean clean, int offX, int offY, int gen) throws Exception {
        long now = System.currentTimeMillis();
        int[] sig = signature(bmp);

        if (ref != null) {
            boolean[] mask = textMask;
            float outside = diff(sig, ref, mask, 1);
            float under = clean ? diff(sig, ref, mask, 2) : 0f;
            prev = sig;
            if (outside < changeThreshold() && under < TEXT_CHANGE_T) return; // nada nuevo
            // La pantalla cambió: hay que volver a traducir cuando se quede quieta.
            ref = null;
            changeAt = now;
            if (!clean || outside >= BIG_CHANGE_T) hideLabels(); // desplazamiento o cambio de página
            return;
        }

        boolean stable = diff(sig, prev, null, 0) < STABLE_T;
        boolean forced = now - changeAt > FORCE_AFTER_MS;
        prev = sig;
        if (!stable && !forced) return;
        if (!clean && overlayShown) {   // la capa saldría en la captura: quitarla y esperar un cuadro
            hideLabels();
            return;
        }

        // ---- OCR
        Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 6, TimeUnit.SECONDS);
        Rect bub = bubbleRect;
        List<Rect> rects = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect r = block.getBoundingBox();
            if (r == null) continue;
            String t = cleanText(block.getText());
            if (!isTranslatable(t)) continue;
            Rect screen = new Rect(r);
            screen.offset(offX, offY);
            if (!clean && Rect.intersects(screen, bub)) continue; // no traducir nuestra burbuja
            rects.add(screen);
            texts.add(t);
            if (rects.size() >= MAX_BLOCKS) break;
        }
        if (gen != generation) return;

        // ---- traducción en paralelo (la caché hace que lo ya visto sea instantáneo)
        List<Future<String>> futures = new ArrayList<>();
        for (String t : texts) {
            futures.add(pool.submit((Callable<String>) () -> translator.translate(t)));
        }
        List<Rect> outRects = new ArrayList<>();
        List<String> outTexts = new ArrayList<>();
        int errors = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                String tr = futures.get(i).get(10, TimeUnit.SECONDS);
                if (tr != null && !tr.trim().isEmpty() && !tr.trim().equalsIgnoreCase(texts.get(i))) {
                    outRects.add(rects.get(i));
                    outTexts.add(tr.trim());
                }
            } catch (Exception e) {
                errors++;
            }
        }
        if (gen != generation) return;
        if (!futures.isEmpty() && errors == futures.size()) {
            toast("No se pudo traducir. Revise Internet o use el motor sin conexión.");
        }

        // ---- marcar las celdas cubiertas y mostrar
        boolean[] mask = new boolean[sig.length];
        float sx = (float) GRID_W / bmp.getWidth(), sy = (float) gridH / bmp.getHeight();
        List<Rect> maskRects = new ArrayList<>(outRects);
        if (!clean) maskRects.add(bub);
        for (Rect r : maskRects) {
            int x0 = Math.max(0, (int) ((r.left - offX) * sx));
            int x1 = Math.min(GRID_W - 1, (int) ((r.right - offX) * sx));
            int y0 = Math.max(0, (int) ((r.top - offY) * sy));
            int y1 = Math.min(gridH - 1, (int) ((r.bottom - offY) * sy));
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) mask[y * GRID_W + x] = true;
        }
        textMask = mask;
        ref = sig;
        overlayShown = !outRects.isEmpty();
        main.post(() -> { if (gen == generation) showLabels(outRects, outTexts); });
    }

    private void hideLabels() {
        overlayShown = false;
        main.post(() -> { if (overlay != null) overlay.removeAllViews(); });
    }

    /** Une líneas cortadas y pasa el TEXTO EN MAYÚSCULAS (típico del manga) a oración normal. */
    static String cleanText(String raw) {
        String s = raw.replaceAll("-\\n(?=\\p{Ll}|\\p{Lu})", "")
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        int letters = 0, upper = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        if (letters >= 3 && upper > letters * 0.8f) {
            StringBuilder sb = new StringBuilder(s.toLowerCase());
            boolean cap = true;
            for (int i = 0; i < sb.length(); i++) {
                char c = sb.charAt(i);
                if (cap && Character.isLetter(c)) {
                    sb.setCharAt(i, Character.toUpperCase(c));
                    cap = false;
                } else if (c == '.' || c == '!' || c == '?') {
                    cap = true;
                }
            }
            s = sb.toString().replaceAll("\\bi\\b", "I").replaceAll("\\bi'", "I'");
        }
        return s;
    }

    private static boolean isTranslatable(String t) {
        int letters = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) letters++;
        return letters >= 2;
    }

    // =================================================================== capa de traducción (main)

    private void addOverlayWindow() {
        if (overlay != null) return;
        overlay = new FrameLayout(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   // los toques pasan al juego/app
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        wm.addView(overlay, lp);
        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.getLocationOnScreen(overlayLoc));

        // La burbuja debe quedar por encima de la capa.
        if (bubble != null) {
            wm.removeView(bubble);
            wm.addView(bubble, bubbleLp);
        }
    }

    private void removeOverlayWindow() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
        }
    }

    private void showLabels(List<Rect> rects, List<String> texts) {
        if (overlay == null || !live) return;
        overlay.getLocationOnScreen(overlayLoc);
        overlay.removeAllViews();
        int alpha = Math.round(Prefs.opacity(this) * 2.55f);

        for (int i = 0; i < rects.size(); i++) {
            Rect r = rects.get(i);
            TextView tv = new TextView(this);
            tv.setText(texts.get(i));
            tv.setTextColor(0xFF111111);
            tv.setGravity(Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.setPadding(dp(2), dp(1), dp(2), dp(1));
            tv.setAutoSizeTextTypeUniformWithConfiguration(6, 22, 1, TypedValue.COMPLEX_UNIT_SP);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(alpha, 255, 255, 255));
            bg.setCornerRadius(dp(6));
            tv.setBackground(bg);

            // Un poco más grande que el texto original para que el español (más largo) quepa.
            int padX = Math.max(dp(2), r.width() / 12), padY = Math.max(dp(2), r.height() / 10);
            int w = Math.max(r.width() + padX * 2, dp(28));
            int h = Math.max(r.height() + padY * 2, dp(16));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = r.left - padX - overlayLoc[0];
            lp.topMargin = r.top - padY - overlayLoc[1];
            overlay.addView(tv, lp);
        }
    }
}
