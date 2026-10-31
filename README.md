# 🎬 Tablet Video Studio (Android APK Autónomo)

Aplicación nativa de edición de video multipista para tablets Android, que integra la suite **React Video Studio** con un motor autónomo en Kotlin basado en **Android Hardware `MediaCodec` (H.264 + AAC) + `Canvas` + `MediaMuxer`**, sin depender de Termux ni de servidores externos.

---

## 🌟 Características Principales

1. **100% Autónomo (Sin Termux):**
   - Toda la interfaz web multipista (`assets/webapp/`) y los clips de demostración (`assets/demo_uploads/`) están embebidos dentro del APK.
   - Al abrir la app, `LocalStudioServer` levanta automáticamente un servidor local interno en `127.0.0.1` con soporte de streaming HTTP `206 Partial Content` (`Range`) para reproducción instantánea en la línea de tiempo.

2. **Motor Hardware Multipista (`AndroidMediaCodecRenderer` + `AudioTrackMixer`):**
   - **Pistas de Video e Imagen:** Soporte de línea de tiempo multipista, corte (*Split*), recorte de extremos (*Trim*), velocidad (`playbackRate`), opacidad, rotación/espejo (`flipX`/`flipY`), brillo, esquinas redondeadas (`borderRadius`), bordes de color (`borderWidth`) y superposición *Picture-in-Picture* (PiP).
   - **Transiciones:** Fundidos de entrada/salida (*Fade*) y desplazamientos (*Slide/Wipe*).
   - **Capas de Texto y Subtítulos:** Renderizado vectorial de alta nitidez con `StaticLayout`, colores personalizados, cajas de fondo redondeadas, bordes contorneados y sombras.
   - **Mezclador de Audio Multipista (`AudioTrackMixer`):** Decodifica el audio de los clips de video y las pistas independientes de música/voz en off, aplica recortes y volúmenes individuales, mezcla en PCM estéreo 44.1 kHz y codifica por hardware a AAC dentro del contenedor `.mp4`.

3. **Integración Nativa con Tablet Android:**
   - **Importación directa desde Galería (`+ Galería Android`):** Selector nativo multi-archivo para videos, audios e imágenes. También permite compartir cualquier video desde la Galería de Android directamente hacia *Tablet Video Studio*.
   - **Exportación directa a Galería y Descargas:** Guarda automáticamente los videos renderizados en `/sdcard/Movies/TabletVideoStudio/` y `/sdcard/Download/` mediante `MediaStore` (compatible con Android 10 a 15 sin permisos invasivos).
   - **Modo Cine Inmersivo:** Oculta las barras del sistema y la barra superior para aprovechar el 100% de la pantalla de la tablet en horizontal o vertical.
   - **Compartir en 1 toque:** Botones nativos para abrir el MP4 en el reproductor del sistema o compartirlo directamente en TikTok, WhatsApp, Instagram Reels o YouTube.

---

## 📂 Estructura del Proyecto

```
editor de video a la tablet/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .github/workflows/build-apk.yml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── webapp/                        # Bundle compilado de React Video Studio
        │   └── demo_uploads/                  # Clips iniciales de video, PiP y música
        ├── java/com/tabletstudio/videoeditor/
        │   ├── MainActivity.kt                # Shell nativo, WebView HW, FileChooser y Bridge
        │   ├── model/
        │   │   └── StudioModels.kt            # Modelos de línea de tiempo y sonda MediaMetadata
        │   ├── engine/
        │   │   ├── AndroidMediaCodecRenderer.kt # Compositor multipista H.264 + Canvas + Muxer
        │   │   └── AudioTrackMixer.kt         # Decodificador y mezclador multipista PCM -> AAC
        │   └── server/
        │       └── LocalStudioServer.kt       # Servidor HTTP local embebido con soporte Range 206
        └── res/
            ├── layout/activity_main.xml
            ├── values/{colors,strings,themes}.xml
            └── xml/file_paths.xml
```
