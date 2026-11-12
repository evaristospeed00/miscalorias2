# MISCALORIAS

![Imagen de MISCALORIAS](miscalorias2.jpeg)

Aplicación Android nativa para llevar un registro personal de comidas, con estimaciones nutricionales genéricas y clasificación de etiquetas visuales en el dispositivo.

**Creador:** Evaristo Suarez Eichelmann  
**Paquete:** `com.evaristosuarez.miscalorias`  
**Versión inicial:** 1.0.0 (versionCode 1)  
**Plataforma:** Android 8.0 o posterior; target API 36

## Funcionalidad

- Registro diario e historial de los últimos siete días, guardados en SQLite local.
- Catálogo inicial de alimentos genéricos, búsqueda, porciones ajustables y creación de alimentos personalizados.
- Cálculo proporcional de calorías, proteína, carbohidratos y grasas.
- Selección de una imagen o captura con la aplicación de cámara disponible; el modelo incluido de ML Kit ejecuta clasificación visual local.
- Etiquetas de imagen como sugerencias para revisión. El modelo no identifica de forma confiable recetas, ingredientes ocultos ni cantidades, y no calcula calorías. La persona confirma el alimento y el peso.
- Meta diaria opcional; no se prescribe un objetivo ni se presentan los datos como consejo médico.
- Sin cuenta, anuncios, analítica propia, llamadas de red ni permiso de Internet. Las imágenes no se guardan en el diario.
- Opción en ajustes para borrar los registros guardados.

## Arquitectura

- Kotlin y Jetpack Compose para interfaz adaptable.
- `data/FoodDatabase.kt`: SQLite privado para referencias y comidas.
- `data/Food.kt`: modelos y cálculo de nutrición por porción.
- `recognition/FoodImageLabeler.kt`: adaptador acotado a ML Kit Image Labeling.
- `MainActivity.kt`: navegación, permisos delegados al selector/cámara del sistema y formularios.
- Pruebas JVM para el cálculo nutricional.

El catálogo incluido es una referencia pequeña y orientativa, no una base clínica ni una medición de recetas. Los registros son copias de los valores usados al agregarlos; cambiar una referencia no reescribe el historial.

## Construir y probar

Requiere JDK 17 y Android SDK Platform/Build Tools 36. Las herramientas de compilación deben estar instaladas localmente; no se guardan dentro del repositorio al publicar.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat bundleRelease
```

### Vista rápida en el navegador

Para ver e interactuar con la app Android real desde una página local, ejecuta:

```powershell
.\preview.bat
```

También puedes abrir `preview.bat` con doble clic. El comando compila, inicia el emulador sin ventana y abre `http://localhost:8765`. Puedes tocar, deslizar y escribir en la pantalla desde el navegador. La primera ejecución puede tardar mientras inicia Android; después usa `.\stop-preview.bat` para cerrar el servidor y el emulador iniciado por este comando. No instala paquetes de npm; usa Node.js y el SDK ya configurado en este equipo. El servidor escucha solo en tu computadora.

Para compilar, iniciar el emulador Android e instalar/abrir la app desde PowerShell en la carpeta del proyecto, ejecuta:

```powershell
.\start.bat
```

La primera ejecución crea el AVD local `MISCALORIAS_API_37` y puede tardar mientras inicia Android. `start`, `.\start.bat` y `.\preview.bat` abren la misma vista localhost; ya no se abre la ventana tradicional del emulador. El atajo `start` se instala en el perfil de Windows PowerShell; abre una consola nueva (o ejecuta `. $PROFILE.CurrentUserAllHosts`) para cargarlo. Fuera de esta carpeta, `start` conserva el comportamiento normal de PowerShell.

`bundleRelease` sin una configuración de firma de publicación produce un AAB no firmado. Para firmar con una clave de carga que controles, configura `MISCALORIAS_KEYSTORE_PATH`, `MISCALORIAS_KEYSTORE_PASSWORD`, `MISCALORIAS_KEY_ALIAS` y `MISCALORIAS_KEY_PASSWORD` en el entorno local seguro y vuelve a ejecutar `bundleRelease`. No guardes estas variables en el repositorio ni uses una clave de depuración. Habilita Play App Signing en Play Console.

## Publicación

1. Revisa `PRIVACY_POLICY.md`, publica su contenido en una URL HTTPS bajo control del editor y pega esa URL en Play Console. El archivo local no equivale a una política ya publicada.
2. Completa la declaración de apps de salud de Play Console como seguimiento de nutrición/alimentación. Confirma la sección de seguridad de datos, el uso del SDK de ML Kit y las políticas vigentes en la fecha de envío; no completes declaraciones por inferencia.
3. Como no hay permiso `INTERNET` ni permisos de almacenamiento/cámara declarados, la selección de medios y cámara se solicita mediante interfaces del sistema. Verifica los permisos efectivos del manifiesto combinado del AAB antes de publicarlo.
4. Genera un AAB de release firmado con tu clave de carga, prueba el artefacto en un canal interno y completa clasificación de contenido, ficha, capturas, icono, formulario de seguridad de datos y demás declaraciones de Play Console.
5. El requisito publicado para nuevas apps y actualizaciones desde el 31 de agosto de 2026 es target API 36; el proyecto usa API 36. Revalida el requisito en la página de Google Play antes del envío.
6. La compilación local no significa que Google Play haya revisado, aprobado o publicado la aplicación.

Referencias oficiales: [requisitos de nivel de API](https://developer.android.com/google/play/requirements/target-sdk) y [declaración de apps de salud](https://support.google.com/googleplay/android-developer/answer/14738291).

No se incluye ni se genera un almacén de firma. La clave de publicación es un secreto que debe custodiar el propietario.
