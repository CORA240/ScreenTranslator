package com.balor.screentranslator;

import android.content.Context;
import android.util.LruCache;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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

    /**
     * Traduce todos los globos de la pantalla. Con el motor en línea los envía juntos
     * (uno por línea) para que la traducción tenga el contexto de la conversación.
     * Devuelve una lista del mismo tamaño; null donde falló.
     */
    public List<String> translateAll(List<String> texts, ExecutorService pool) {
        String engine = Prefs.engine(ctx);
        String src = Prefs.sourceLang(ctx);
        String tgt = Prefs.targetLang(ctx);
        String prefix = engine + "|" + src + "|" + tgt + "|";

        List<String> out = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String hit = cache.get(prefix + texts.get(i));
            out.add(hit);
            if (hit == null) missing.add(i);
        }
        if (missing.isEmpty()) return out;

        if (!Prefs.ENGINE_OFFLINE.equals(engine) && missing.size() > 1) {
            // Lotes de ~1500 caracteres para no exceder el largo de la URL.
            int from = 0;
            while (from < missing.size()) {
                int to = from, len = 0;
                while (to < missing.size() && (to == from || len + texts.get(missing.get(to)).length() < 1500)) {
                    len += texts.get(missing.get(to)).length() + 1;
                    to++;
                }
                List<Integer> chunk = missing.subList(from, to);
                StringBuilder joined = new StringBuilder();
                for (int k = 0; k < chunk.size(); k++) {
                    if (k > 0) joined.append('\n');
                    joined.append(texts.get(chunk.get(k)).replace('\n', ' '));
                }
                try {
                    String[] parts = OnlineTranslator.translate(joined.toString(), src, tgt).split("\n", -1);
                    if (parts.length == chunk.size()) {
                        for (int k = 0; k < chunk.size(); k++) {
                            int idx = chunk.get(k);
                            String tr = parts[k].trim();
                            if (!tr.isEmpty()) {
                                out.set(idx, tr);
                                cache.put(prefix + texts.get(idx), tr);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Si el lote falla, se traduce globo por globo abajo.
                }
                from = to;
            }
        }

        // Lo que falte (o el motor sin conexión): uno por uno, en paralelo.
        List<Integer> idx = new ArrayList<>();
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (out.get(i) != null) continue;
            final String t = texts.get(i);
            idx.add(i);
            futures.add(pool.submit(() -> translate(t)));
        }
        for (int k = 0; k < futures.size(); k++) {
            try {
                out.set(idx.get(k), futures.get(k).get(12, TimeUnit.SECONDS));
            } catch (Exception ignored) {
                // queda en null
            }
        }
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
