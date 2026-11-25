package com.balor.screentranslator;

import android.content.Context;
import android.util.LruCache;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.concurrent.TimeUnit;

/**
 * Traduce con el motor elegido (en línea Google o sin conexión ML Kit) y guarda caché,
 * así el texto que ya se tradujo aparece al instante al volver a verlo.
 * Llamar solo desde hilos de fondo.
 */
public final class TextTranslator {

    private final LruCache<String, String> cache = new LruCache<>(2000);
    private final Context ctx;

    private Translator offline;
    private String offlineKey;

    public TextTranslator(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public String translate(String text) throws Exception {
        String engine = Prefs.engine(ctx);
        String src = Prefs.sourceLang(ctx);
        String tgt = Prefs.targetLang(ctx);
        String key = engine + "|" + src + "|" + tgt + "|" + text;
        String hit = cache.get(key);
        if (hit != null) return hit;

        String out;
        if (Prefs.ENGINE_OFFLINE.equals(engine)) {
            out = Tasks.await(offlineClient(src, tgt).translate(text), 10, TimeUnit.SECONDS);
        } else {
            out = OnlineTranslator.translate(text, src, tgt);
        }
        cache.put(key, out);
        return out;
    }

    /** Prepara el modelo sin conexión (lo descarga la primera vez, ~30 MB por idioma). */
    public void prepareOffline() throws Exception {
        offlineClient(Prefs.sourceLang(ctx), Prefs.targetLang(ctx));
    }

    private synchronized Translator offlineClient(String src, String tgt) throws Exception {
        String s = mlkitLang("auto".equals(src) ? "en" : src);
        String t = mlkitLang(tgt);
        if (s == null || t == null) throw new Exception("Idioma no disponible sin conexión");
        String k = s + ">" + t;
        if (offline != null && k.equals(offlineKey)) return offline;
        if (offline != null) offline.close();
        offline = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(s).setTargetLanguage(t).build());
        offlineKey = k;
        Tasks.await(offline.downloadModelIfNeeded(new DownloadConditions.Builder().build()),
                5, TimeUnit.MINUTES);
        return offline;
    }

    private static String mlkitLang(String code) {
        int dash = code.indexOf('-');
        return TranslateLanguage.fromLanguageTag(dash > 0 ? code.substring(0, dash) : code);
    }

    public synchronized void close() {
        if (offline != null) offline.close();
        offline = null;
    }
}
