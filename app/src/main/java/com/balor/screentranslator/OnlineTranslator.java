package com.balor.screentranslator;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Traducción en línea usando el endpoint público de Google Translate (sin API key). */
public final class OnlineTranslator {

    private OnlineTranslator() {}

    /** @param sourceLang código de idioma o "auto". */
    public static String translate(String text, String sourceLang, String targetLang) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx"
                + "&sl=" + URLEncoder.encode(sourceLang, "UTF-8")
                + "&tl=" + URLEncoder.encode(targetLang, "UTF-8")
                + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            if (conn.getResponseCode() != 200) {
                throw new Exception("HTTP " + conn.getResponseCode());
            }
            JSONArray sentences = new JSONArray(readAll(conn.getInputStream())).getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                JSONArray s = sentences.optJSONArray(i);
                if (s != null && !s.isNull(0)) sb.append(s.getString(0));
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
