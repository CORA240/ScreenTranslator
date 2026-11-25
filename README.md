# Screen Translator v2 – traductor en vivo para manga y juegos (Android)

Burbuja flotante que reconoce el texto de la pantalla, **también dentro de imágenes, manga, webtoons y juegos**,
y coloca la traducción encima. La traducción se **actualiza sola** al pasar de página, desplazarse o cambiar el diálogo.

- Requiere **Android 11 o superior**. En **Android 14+** funciona más fluido, sin parpadeo:
  captura solo la ventana del juego o la app, así la capa de traducción no estorba.
- OCR: Google ML Kit, sin conexión, con el modelo incluido en el APK (alfabeto latino: inglés, español, etc.).
- Traducción: **en línea** (Google, mejor calidad) o **sin conexión** (ML Kit, descarga ~30 MB la primera vez).
- La capa no bloquea los toques: puede seguir jugando o deslizando mientras se ve la traducción.
- Las frases ya traducidas se guardan en caché y reaparecen al instante.
- Convierte el TEXTO EN MAYÚSCULAS del manga a oraciones normales antes de traducir, para una mejor traducción.

## Obtener el APK

### Opción A: GitHub (sin instalar nada)
1. Cree un repositorio nuevo en GitHub y suba esta carpeta completa (incluida `.github/`).
2. En la pestaña **Actions** corre solo el flujo "Compilar APK" (unos 3–5 minutos).
3. Abra la ejecución terminada y descargue el artefacto **ScreenTranslator-apk**.

### Opción B: Android Studio
File → Open → carpeta `ScreenTranslator` → Build → Build APK(s).

## Instalar y usar
1. Instale el APK (permita "orígenes desconocidos").
2. Abra la app → **Activar en Accesibilidad** → active "Screen Translator".
   En Android 13+ si dice "Ajuste restringido": Ajustes → Apps → Screen Translator → ⋮ → *Permitir ajustes restringidos*.
3. En los ajustes de la app elija el idioma de origen (English) y el de destino (Español).
4. Abra el manga o el juego y toque la burbuja: se pone **verde (ON)** y empieza a traducir en vivo.
   Tóquela otra vez para apagar. Si la mantiene presionada, abre los ajustes.

### Sensibilidad
- Manga y webtoon: 7–10. Detecta enseguida cada cambio de página o desplazamiento.
- Juegos con fondo animado: 2–4. Ignora las animaciones y solo vuelve a traducir cuando cambia el texto.

## Limitaciones
- Apps que bloquean capturas de pantalla (algunas de streaming o de bancos) no se pueden traducir.
- El OCR incluido lee alfabeto latino. Para manga en japonés, coreano o chino se necesitaría agregar
  el modelo de OCR de ese idioma.
