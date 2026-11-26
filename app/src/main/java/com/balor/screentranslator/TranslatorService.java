package com.balor.screentranslator;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final long IDLE_SLOW_MS = 4_000;     // sin cambios 4 s  → revisar cada 0.8 s
    private static final long IDLE_SLOWER_MS = 15_000;  // sin cambios 15 s → revisar cada 1.5 s
    private static final long IDLE_PAUSE_MS = 5 * 60_000; // sin cambios 5 min → pausar solo
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
        translator = new TextTranslator(this);
        // Siempre arranca APAGADO (también al reiniciar el teléfono): cero consumo hasta que se encienda.
        Prefs.setBubbleEnabled(this, false);
        BubbleTileService.refresh(this);
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOff, f);
    }

    /** Al apagar la pantalla se pausa la traducción para no gastar batería. */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            if (live) stopLive();
        }
    };

    /** Muestra u oculta la burbuja según el ajuste (lo llaman el botón del panel rápido y la app). */
    public static void applyBubblePref(android.content.Context ctx) {
        TranslatorService s = instance;
        if (s != null) s.main.post(s::updateBubbleVisibility);
        BubbleTileService.refresh(ctx);
    }

    private void updateBubbleVisibility() {
        boolean want = Prefs.bubbleEnabled(this);
        if (want && bubble == null) {
            showBubble();
            startLive();                       // encender = mostrar burbuja y empezar a traducir
        } else if (!want && bubble != null) {
            stopLive();
            dismissMenu();
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
            // Liberar OCR y modelos de memoria mientras está apagado.
            worker.execute(() -> {
                if (recognizer != null) { recognizer.close(); recognizer = null; }
            pixBuf = null;
                translator.close();
            });
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // La app avisa cuánto se desplazó: mover las traducciones al instante, sin esperar captura.
        if (!live || event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (getPackageName().contentEquals(String.valueOf(event.getPackageName()))) return;
        int dy = event.getScrollDeltaY();
        if (dy == -1 || dy == 0) return;
        scrollPos += dy;
        lastScrollEvt = System.currentTimeMillis();
        lastActivity = lastScrollEvt;
        applyScroll();
        if (!inFlight) scheduleNext(0);           // capturar ya para traducir lo que va entrando
    }

    // ---- seguimiento del scroll (hilo principal)
    private float scrollPos;                // desplazamiento acumulado del contenido, en píxeles
    private float labelsScroll;             // scrollPos en el momento de la captura de las etiquetas
    private volatile long lastScrollEvt;
    private boolean inFlight;               // hay una captura en proceso
    private FrameLayout content;            // contenedor de etiquetas que se mueve con el scroll

    private void applyScroll() {
        if (content != null) content.setTranslationY(-(scrollPos - labelsScroll));
    }
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        stopLive();
        dismissMenu();
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        worker.shutdownNow();
        pool.shutdownNow();
        if (recognizer != null) recognizer.close();
        if (translator != null) translator.close();
        instance = null;
        BubbleTileService.refresh(this);
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
        if (bubble == null) return;
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
            showMenu();
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

    // ---- menú al mantener presionada la burbuja
    private android.widget.LinearLayout menu;

    private void showMenu() {
        dismissMenu();
        menu = new android.widget.LinearLayout(this);
        menu.setOrientation(android.widget.LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2FFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFBDBDBD);
        menu.setBackground(bg);
        menu.setElevation(dp(8));
        menu.setPadding(dp(4), dp(4), dp(4), dp(4));
        menu.addView(menuItem(live ? "■  Detener traducción" : "▶  Traducir", () -> {
            if (live) stopLive(); else startLive();
        }));
        menu.addView(menuItem("⚙  Ajustes", this::openSettings));
        menu.addView(menuItem("⏻  Apagar traductor", () -> {
            Prefs.setBubbleEnabled(this, false);
            updateBubbleVisibility();
            BubbleTileService.refresh(this);
            Toast.makeText(this, "Traductor apagado. Enciéndalo con el botón \"Traductor\" del panel rápido.",
                    Toast.LENGTH_LONG).show();
        }));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean rightSide = bubbleLp.x > dm.widthPixels / 2;
        lp.x = rightSide ? Math.max(0, bubbleLp.x - dp(200)) : bubbleLp.x + bubbleLp.width + dp(4);
        lp.y = bubbleLp.y;
        menu.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) dismissMenu();
            return false;
        });
        wm.addView(menu, lp);
    }

    private TextView menuItem(String label, Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(0xFF212121);
        t.setPadding(dp(14), dp(12), dp(18), dp(12));
        t.setOnClickListener(v -> {
            dismissMenu();
            action.run();
        });
        return t;
    }

    private void dismissMenu() {
        if (menu != null) {
            try { wm.removeView(menu); } catch (Exception ignored) {}
            menu = null;
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
        lastActivity = System.currentTimeMillis();
        scrollPos = 0;
        labelsScroll = 0;
        worker.execute(() -> {
            resetState();
            if (recognizer == null) {
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }
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
        inFlight = false;
        generation++;
        main.removeCallbacks(tickRunnable);
        if (bubble != null) paintBubble();
        removeOverlayWindow();
    }

    private final Runnable tickRunnable = this::tick;

    /** Última vez que la pantalla cambió: si no cambia, se revisa menos seguido (ahorra batería). */
    private volatile long lastActivity;

    private long nextInterval() {
        long idle = System.currentTimeMillis() - lastActivity;
        if (idle > IDLE_PAUSE_MS) {
            stopLive();
            Toast.makeText(this, "Traducción en pausa (sin cambios en 5 min). Toque la burbuja para seguir.",
                    Toast.LENGTH_LONG).show();
            return -1;
        }
        if (idle < 1500) return 340;            // desplazándose: lo más rápido que permite Android
        if (idle > IDLE_SLOWER_MS) return 1500;
        if (idle > IDLE_SLOW_MS) return 800;
        return INTERVAL_MS;
    }

    private void scheduleNext(long delay) {
        main.removeCallbacks(tickRunnable);
        if (live) main.postDelayed(tickRunnable, delay);
    }

    /** Hilo principal: pide una captura. */
    private void tick() {
        if (!live || inFlight) return;
        final int gen = generation;
        final float scrollAt = scrollPos;
        inFlight = true;

        // Android 14+: capturar solo la ventana de la app (sin nuestra capa encima).
        if (Build.VERSION.SDK_INT >= 34 && windowModeOk) {
            AccessibilityWindowInfo w = findAppWindow();
            if (ownFront) { inFlight = false; scheduleNext(1000); return; }
            if (w != null) {
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                takeScreenshotOfWindow(w.getId(), worker, new Shot(gen, true, b.left, b.top, scrollAt));
                return;
            }
        } else if (ownAppInFront()) {
            inFlight = false;
            scheduleNext(1000);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, new Shot(gen, false, 0, 0, scrollAt));
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
        final float scrollAt;   // scroll en el momento de pedir la captura

        Shot(int gen, boolean clean, int offX, int offY, float scrollAt) {
            this.gen = gen; this.clean = clean; this.offX = offX; this.offY = offY; this.scrollAt = scrollAt;
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
                if (bmp != null && gen == generation) process(bmp, clean, offX, offY, gen, scrollAt);
            } catch (Exception e) {
                // Captura u OCR fallidos: se reintenta en el siguiente ciclo.
            } finally {
                if (bmp != null) bmp.recycle();
                main.post(() -> {
                    inFlight = false;
                    if (gen != generation) return;
                    long next = nextInterval();
                    if (next > 0) scheduleNext(next);
                });
            }
        }

        @Override
        public void onFailure(int errorCode) {
            main.post(() -> inFlight = false);
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
        prevProfile = null;
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

    private int[] prevProfile;     // perfil por filas del cuadro anterior (worker)
    private int[] pixBuf;          // memoria reutilizada para los píxeles de cada captura

    private int[] profile(Bitmap bmp) {
        int w = 32, h = Math.max(16, bmp.getHeight() / 4);
        Bitmap small = Bitmap.createScaledBitmap(bmp, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
        }
        return Motion.rowProfile(px, w, h);
    }

    private void process(Bitmap bmp, boolean clean, int offX, int offY, int gen, float scrollAt) throws Exception {
        long now = System.currentTimeMillis();
        int[] sig = signature(bmp);

        // ---- ¿se desplazó la pantalla? (para apps que no avisan su scroll)
        int[] prof = profile(bmp);
        int shiftRows = Motion.verticalShift(prevProfile, prof);
        prevProfile = prof;
        boolean eventsActive = now - lastScrollEvt < 800;
        float shiftPx = 0;
        if (shiftRows != Integer.MIN_VALUE) {
            shiftPx = shiftRows * (bmp.getHeight() / (float) (prof.length / Motion.BANDS));
            lastActivity = now;
            if (!eventsActive) {
                final float d = shiftPx;
                main.post(() -> { scrollPos += d; applyScroll(); });
            }
        }
        boolean scrolling = eventsActive || shiftRows != Integer.MIN_VALUE;
        // Posición de scroll que corresponde a ESTE cuadro.
        final float frameScroll = scrollAt + (eventsActive ? 0 : shiftPx);

        if (clean) {
            // Android 14+: la captura no incluye nuestra capa → se puede traducir mientras se mueve.
            if (ref != null && !scrolling) {
                boolean[] mask = textMask;
                float outside = diff(sig, ref, mask, 1);
                float under = diff(sig, ref, mask, 2);
                if (outside < changeThreshold() && under < TEXT_CHANGE_T) { prev = sig; return; }
                ref = null;
                changeAt = now;
                lastActivity = now;
                if (outside >= BIG_CHANGE_T) hideLabels();          // cambio de página
            }
            if (!scrolling && ref == null) {
                // Cambio sin desplazamiento (página nueva, juego): esperar a que se quede quieta.
                boolean stable = diff(sig, prev, null, 0) < STABLE_T;
                boolean forced = now - changeAt > FORCE_AFTER_MS;
                prev = sig;
                if (!stable && !forced) return;
            } else if (!scrolling) {
                prev = sig;
                return;
            }
            prev = sig;
        } else {
            // Android 11–13: nuestra capa sale en la captura → ocultarla y esperar a que se quede quieta.
            if (ref != null) {
                boolean[] mask = textMask;
                float outside = diff(sig, ref, mask, 1);
                prev = sig;
                if (outside < changeThreshold()) return;
                ref = null;
                changeAt = now;
                lastActivity = now;
                hideLabels();
                return;
            }
            boolean stable = diff(sig, prev, null, 0) < STABLE_T;
            boolean forced = now - changeAt > FORCE_AFTER_MS;
            prev = sig;
            if (!stable && !forced) return;
            if (overlayShown) { hideLabels(); return; }
        }

        // ---- OCR (un poco menos de ampliación mientras se desplaza, para responder más rápido)
        float up = bmp.getWidth() < 1440 ? (scrolling ? 1.25f : 1.5f) : 1f;
        Bitmap ocrBmp = up > 1f
                ? Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * up), Math.round(bmp.getHeight() * up), true)
                : bmp;
        Text result;
        try {
            result = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBmp, 0)), 8, TimeUnit.SECONDS);
        } finally {
            if (ocrBmp != bmp) ocrBmp.recycle();
        }

        List<Bubbles.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r == null || line.getText() == null) continue;
                lines.add(new Bubbles.Line(Math.round(r.left / up), Math.round(r.top / up),
                        Math.round(r.right / up), Math.round(r.bottom / up), line.getText()));
            }
        }

        // Brillo de la captura, para encontrar el globo blanco alrededor de cada texto.
        final int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (pixBuf == null || pixBuf.length != bw * bh) pixBuf = new int[bw * bh];
        final int[] pixels = pixBuf;
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh);
        Motion.Lum lum = (x, y) -> {
            int c = pixels[y * bw + x];
            return (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
        };

        Rect bub = bubbleRect;
        int edge = Math.max(4, bh / 100);
        List<Rect> rects = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Bubbles.Bubble b : Bubbles.group(lines)) {
            if (b.soundEffect) continue;            // no traducir "BOOM", "CRASH", etc.
            // Globo a medio entrar por arriba/abajo: esperar a que se vea completo.
            if (b.top <= edge || b.bottom >= bh - edge) continue;
            String t = cleanText(b.text);
            if (!isTranslatable(t)) continue;

            int[] box = {b.left, b.top, b.right, b.bottom};
            int maxGX = Math.round(Math.max(b.lineHeight * 3f, (b.right - b.left) * 0.45f));
            int maxGY = Math.round(b.lineHeight * 2.5f);
            Rect r;
            if (Motion.expandBalloon(lum, bw, bh, box, maxGX, maxGY)) {
                r = new Rect(box[0], box[1], box[2], box[3]);      // cubrir el interior del globo
            } else {
                r = padded(new Rect(b.left, b.top, b.right, b.bottom), b.lineHeight);
            }
            r.offset(offX, offY);
            if (!clean && Rect.intersects(r, bub)) continue;   // no traducir nuestra burbuja
            rects.add(r);
            texts.add(t);
            if (rects.size() >= MAX_BLOCKS) break;
        }
        if (gen != generation) return;

        // ---- traducción: todos los globos juntos para que tenga el contexto de la conversación
        List<String> translated = translator.translateAll(texts, pool);
        List<Rect> outRects = new ArrayList<>();
        List<String> outTexts = new ArrayList<>();
        int errors = 0;
        for (int i = 0; i < texts.size(); i++) {
            String tr = translated.get(i);
            if (tr == null) { errors++; continue; }
            if (!tr.trim().isEmpty() && !tr.trim().equalsIgnoreCase(texts.get(i))) {
                outRects.add(rects.get(i));
                outTexts.add(tr.trim());
            }
        }
        if (gen != generation) return;
        if (!texts.isEmpty() && errors == texts.size()) {
            toast("No se pudo traducir. Revise Internet o use el motor sin conexión.");
        }

        // ---- marcar las celdas cubiertas y mostrar
        boolean[] mask = new boolean[sig.length];
        float sx = (float) GRID_W / bw, sy = (float) gridH / bh;
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
        main.post(() -> { if (gen == generation) showLabels(outRects, outTexts, frameScroll); });
    }

    private void hideLabels() {
        overlayShown = false;
        main.post(() -> { if (content != null) content.removeAllViews(); });
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
            // Errores típicos del OCR con letra de cómic (todo en mayúsculas).
            s = s.replace('|', 'I')
                    .replaceAll("(?<=\\p{Lu})0(?=\\p{Lu})|(?<=\\s)0(?=\\p{Lu}{2})", "O")
                    .replaceAll("\\bl\\b", "I")
                    .replaceAll("(?<=\\p{Lu})l(?=\\p{Lu})", "I")
                    .replaceAll("\\s+([!?.,])", "$1")
                    .replaceAll("([!?])\\s+(?=[!?])", "$1");
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

    /** Descarta números, símbolos y onomatopeyas sin vocales ("HMM", "TSK", "GRR"). */
    static boolean isTranslatable(String t) {
        int letters = 0;
        boolean vowel = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ("aeiouyáéíóúAEIOUYÁÉÍÓÚ".indexOf(c) >= 0) vowel = true;
            }
        }
        return letters >= 2 && vowel;
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
        content = new FrameLayout(this);
        overlay.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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
            content = null;
        }
    }

    /** Zona que cubre la etiqueta cuando no hay globo blanco: el texto más un margen. */
    private Rect padded(Rect r, float lineH) {
        int padX = Math.round(Math.max(dp(4), lineH * 0.55f));
        int padY = Math.round(Math.max(dp(3), lineH * 0.40f));
        Rect out = new Rect(r.left - padX, r.top - padY, r.right + padX, r.bottom + padY);
        if (out.width() < dp(30)) out.inset(-(dp(30) - out.width()) / 2, 0);
        if (out.height() < dp(18)) out.inset(0, -(dp(18) - out.height()) / 2);
        return out;
    }

    private void showLabels(List<Rect> rects, List<String> texts, float captureScroll) {
        if (overlay == null || content == null || !live) return;
        overlay.getLocationOnScreen(overlayLoc);
        content.removeAllViews();
        labelsScroll = captureScroll;
        applyScroll();
        int alpha = Math.round(Prefs.opacity(this) * 2.55f);
        Typeface face = Typeface.create("sans-serif-condensed", Typeface.BOLD);

        for (int i = 0; i < rects.size(); i++) {
            Rect box = rects.get(i);
            String text = texts.get(i);

            TextView tv = new TextView(this);
            tv.setTypeface(face);
            tv.setTextColor(0xFF111111);
            tv.setGravity(Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);
            tv.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            int inner = dp(3);
            tv.setPadding(inner, inner, inner, inner);

            // Tamaño de letra más grande que quepa SIN partir palabras; si no cabe, la caja crece.
            float[] fit = fitText(text, tv.getPaint(), box.width() - inner * 2, box.height() - inner * 2);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, fit[0]);
            int w = Math.max(box.width(), Math.round(fit[1]) + inner * 2);
            int h = Math.max(box.height(), Math.round(fit[2]) + inner * 2);
            tv.setText(text);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(alpha, 255, 255, 255));
            bg.setCornerRadius(Math.min(dp(14), Math.min(w, h) / 3f));
            tv.setBackground(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = box.centerX() - w / 2 - overlayLoc[0];
            lp.topMargin = box.centerY() - h / 2 - overlayLoc[1];
            content.addView(tv, lp);
        }
    }

    /**
     * Busca el tamaño de letra más grande con el que el texto cabe en la caja sin cortar
     * ninguna palabra. Si no cabe, prueba ensanchando la caja un poco.
     * @return {tamaño en px, ancho usado en px, alto del texto en px}
     */
    private float[] fitText(String text, android.text.TextPaint basePaint, int width, int height) {
        android.text.TextPaint paint = new android.text.TextPaint(basePaint);
        float density = getResources().getDisplayMetrics().scaledDensity;
        float[] widthFactors = {1f, 1.25f, 1.5f};
        float[] minSp = {8f, 7f, 6f};
        for (int f = 0; f < widthFactors.length; f++) {
            int w = Math.max(1, Math.round(width * widthFactors[f]));
            for (float sp = 22f; sp >= minSp[f]; sp -= 0.5f) {
                paint.setTextSize(sp * density);
                android.text.StaticLayout layout = android.text.StaticLayout.Builder
                        .obtain(text, 0, text.length(), paint, w)
                        .setIncludePad(false)
                        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                        .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                        .build();
                if (layout.getHeight() <= height && !breaksWords(layout, text)) {
                    return new float[]{sp * density, w, layout.getHeight()};
                }
            }
        }
        // No cabe ni con letra mínima: usar 7sp y dejar que la caja crezca hacia abajo.
        int w = Math.max(1, Math.round(width * 1.5f));
        paint.setTextSize(7f * density);
        android.text.StaticLayout layout = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, w).setIncludePad(false).build();
        return new float[]{7f * density, w, layout.getHeight()};
    }

    private static boolean breaksWords(android.text.StaticLayout layout, String text) {
        for (int i = 0; i < layout.getLineCount() - 1; i++) {
            int end = layout.getLineEnd(i);
            if (end > 0 && end < text.length()
                    && Character.isLetterOrDigit(text.charAt(end - 1))
                    && Character.isLetterOrDigit(text.charAt(end))) {
                return true;
            }
        }
        return false;
    }
}
