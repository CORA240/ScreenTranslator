package com.screentranslator.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.TextView;

/** Ajustes: idiomas, motor de traducción, sensibilidad, opacidad y activación del servicio. */
public class MainActivity extends Activity {

    private TextView status;
    private Button toggle;
    private Switch bubbleSwitch;
    private LinearLayout root;

    private interface OnPick { void pick(int index); }
    private interface OnSlide { void slide(int value); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Screen Translator");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(0xFF1565C0);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        toggle = new Button(this);
        toggle.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(toggle);

        bubbleSwitch = new Switch(this);
        bubbleSwitch.setText("Traductor encendido");
        bubbleSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bubbleSwitch.setPadding(0, dp(16), 0, dp(4));
        bubbleSwitch.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setBubbleEnabled(this, checked);
            TranslatorService.applyBubblePref(this);
        });
        root.addView(bubbleSwitch);

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Button addTile = new Button(this);
            addTile.setText("Agregar botón \"Traductor\" al panel rápido");
            addTile.setOnClickListener(v -> {
                android.app.StatusBarManager sbm = getSystemService(android.app.StatusBarManager.class);
                sbm.requestAddTileService(
                        new android.content.ComponentName(this, BubbleTileService.class),
                        "Traductor",
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile),
                        getMainExecutor(),
                        result -> { });
            });
            root.addView(addTile);
        }

        spinner("Idioma del texto (origen):", Prefs.SRC_NAMES,
                indexOf(Prefs.SRC_CODES, Prefs.sourceLang(this)),
                i -> Prefs.setSourceLang(this, Prefs.SRC_CODES[i]));

        spinner("Traducir al idioma:", Prefs.LANG_NAMES,
                indexOf(Prefs.LANG_CODES, Prefs.targetLang(this)),
                i -> Prefs.setTargetLang(this, Prefs.LANG_CODES[i]));

        spinner("Motor de traducción:",
                new String[]{"En línea – Google (mejor calidad)", "Sin conexión – ML Kit (más rápido, sin Internet)"},
                Prefs.ENGINE_OFFLINE.equals(Prefs.engine(this)) ? 1 : 0,
                i -> Prefs.setEngine(this, i == 1 ? Prefs.ENGINE_OFFLINE : Prefs.ENGINE_ONLINE));

        slider("Sensibilidad a cambios (alta para manga, baja para juegos con animación): ",
                1, 10, Prefs.sensitivity(this), "", v -> Prefs.setSensitivity(this, v));

        slider("Opacidad del fondo de la traducción: ",
                40, 100, Prefs.opacity(this), "%", v -> Prefs.setOpacity(this, v));

        TextView help = new TextView(this);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        help.setTextColor(Color.DKGRAY);
        help.setPadding(0, dp(20), 0, 0);
        help.setText("Encender y apagar (empieza siempre apagado):\n"
                + "• Botón \"Traductor\" del panel rápido (deslice la barra de arriba hacia abajo): "
                + "un toque lo enciende y aparece la burbuja; otro toque lo apaga y la burbuja desaparece.\n"
                + "• Mantener presionada la burbuja → Apagar traductor.\n"
                + "• El interruptor \"Traductor encendido\" de esta pantalla.\n\n"
                + "Ahorro de batería: apagado no consume nada. Encendido, revisa la pantalla más lento "
                + "cuando no hay cambios, se pausa al apagar la pantalla y tras 5 minutos sin cambios.\n\n"
                + "Cómo usarla:\n"
                + "1. Pulse el botón de arriba, busque \"Screen Translator\" en Accesibilidad y actívelo.\n"
                + "2. Aparece una burbuja azul (文A). Arrástrela a una esquina donde no estorbe.\n"
                + "3. Abra su manga o juego y toque la burbuja: se pone verde (ON) y la traducción "
                + "se actualiza sola al pasar de página, desplazarse o cambiar el diálogo.\n"
                + "4. Puede seguir tocando y deslizando la app normalmente; la capa no bloquea los toques.\n"
                + "5. Toque la burbuja otra vez para detener. Manténgala presionada para ver el menú.\n\n"
                + "Consejos:\n"
                + "• Manga/webtoon: sensibilidad 7–10.\n"
                + "• Juegos con fondos animados: sensibilidad 2–4.\n"
                + "• En Android 14 o superior la actualización es más fluida (sin parpadeo).\n"
                + "• El motor sin conexión descarga un modelo de ~30 MB la primera vez.");
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private void spinner(String title, String[] items, int selected, OnPick onPick) {
        root.addView(label(title));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(selected);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onPick.pick(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        root.addView(sp);
    }

    private void slider(String title, int min, int max, int value, String unit, OnSlide onSlide) {
        TextView lbl = label(title + value + unit);
        root.addView(lbl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                onSlide.slide(p + min);
                lbl.setText(title + (p + min) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = TranslatorService.isRunning();
        status.setText(on ? "✔ Servicio activo: la burbuja flotante está disponible."
                : "✖ Servicio desactivado. Actívelo para mostrar la burbuja.");
        status.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
        toggle.setText(on ? "Ajustes de accesibilidad (desactivar)" : "Activar en Accesibilidad");
        bubbleSwitch.setChecked(Prefs.bubbleEnabled(this));
        bubbleSwitch.setEnabled(on);
    }
}
