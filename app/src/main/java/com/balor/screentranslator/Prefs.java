package com.balor.screentranslator;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String[] LANG_NAMES = {
            "Español", "English", "Português", "Français", "Deutsch", "Italiano",
            "中文 (简体)", "日本語", "한국어", "Русский", "العربية", "हिन्दी"
    };
    public static final String[] LANG_CODES = {
            "es", "en", "pt", "fr", "de", "it", "zh-CN", "ja", "ko", "ru", "ar", "hi"
    };

    /** Idiomas de origen: el OCR incluido lee alfabeto latino. */
    public static final String[] SRC_NAMES = {
            "Automático", "English", "Español", "Português", "Français", "Deutsch", "Italiano"
    };
    public static final String[] SRC_CODES = {
            "auto", "en", "es", "pt", "fr", "de", "it"
    };

    public static final String ENGINE_ONLINE = "online";
    public static final String ENGINE_OFFLINE = "offline";

    private static final String FILE = "settings";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Si la burbuja flotante se muestra (se puede ocultar sin desactivar el servicio). */
    public static boolean bubbleEnabled(Context c) { return sp(c).getBoolean("bubble_enabled", true); }
    public static void setBubbleEnabled(Context c, boolean v) { sp(c).edit().putBoolean("bubble_enabled", v).apply(); }

    public static String targetLang(Context c) { return sp(c).getString("target_lang", "es"); }
    public static void setTargetLang(Context c, String v) { sp(c).edit().putString("target_lang", v).apply(); }

    public static String sourceLang(Context c) { return sp(c).getString("source_lang", "en"); }
    public static void setSourceLang(Context c, String v) { sp(c).edit().putString("source_lang", v).apply(); }

    public static String engine(Context c) { return sp(c).getString("engine", ENGINE_ONLINE); }
    public static void setEngine(Context c, String v) { sp(c).edit().putString("engine", v).apply(); }

    /** Opacidad del fondo de las etiquetas, 40..100 (%). */
    public static int opacity(Context c) { return sp(c).getInt("overlay_opacity", 95); }
    public static void setOpacity(Context c, int v) { sp(c).edit().putInt("overlay_opacity", v).apply(); }

    /**
     * Sensibilidad a cambios, 1..10. Alta = reacciona a cambios pequeños (manga, texto).
     * Baja = ignora animaciones de fondo (juegos).
     */
    public static int sensitivity(Context c) { return sp(c).getInt("sensitivity", 7); }
    public static void setSensitivity(Context c, int v) { sp(c).edit().putInt("sensitivity", v).apply(); }
}
