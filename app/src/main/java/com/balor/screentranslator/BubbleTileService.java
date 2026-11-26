package com.balor.screentranslator;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Botón "Traductor" del panel rápido: un toque enciende (burbuja + traducción) o apaga todo. */
public class BubbleTileService extends TileService {

    /** Pide a Android que actualice el estado del botón. */
    public static void refresh(Context ctx) {
        try {
            TileService.requestListeningState(ctx, new ComponentName(ctx, BubbleTileService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (!TranslatorService.isRunning()) {
            // El servicio de accesibilidad está apagado: abrir la app para activarlo.
            Toast.makeText(this, "Active primero el servicio en Accesibilidad", Toast.LENGTH_LONG).show();
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(i);
            }
            return;
        }
        Prefs.setBubbleEnabled(this, !Prefs.bubbleEnabled(this));
        TranslatorService.applyBubblePref(this);
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = TranslatorService.isRunning() && Prefs.bubbleEnabled(this);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Traductor");
        tile.setSubtitle(!TranslatorService.isRunning() ? "Sin permiso" : on ? "Encendido" : "Apagado");
        tile.updateTile();
    }
}
