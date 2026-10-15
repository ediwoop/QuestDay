# QuestDay

**Pequeños hábitos. Grandes aventuras.** Primera versión Android de GQUEST.

Aplicación nativa Kotlin y Jetpack Compose, con Room, DataStore y WorkManager. Android 8 o posterior, orientación vertical. No necesita cuenta ni servidor.

## Funciona en esta versión

- Inicio con nombre y selección de primeros hábitos.
- Hoy: misiones programadas, avance por unidades, XP y niveles.
- Hábitos por días de semana, tareas con fecha y objetivos acumulativos; edición y archivo.
- 20/40/60 XP por dificultad, límite 600 XP al día y 100 niveles. Deshacer no duplica el XP.
- Rachas según días programados y registro de descansos.
- Cinco mundos y 75 etapas. Cada 100 XP desbloquea una etapa con cofre de recompensa fija, una sola recogida.
- Monedas y cristales para avatares, inventario persistente.
- Estadísticas semanales, calendario de 28 días y rachas por hábito.
- Recordatorio local opcional; requiere permiso en Android 13+ y puede retrasarse por la batería.
- Exportar y restaurar copias JSON desde el selector de archivos de Android. Restaurar sustituye datos existentes tras confirmar y validar.
- Privacidad y contacto integrados. Sin publicidad, analítica ni servicios remotos.

## Piloto: La última antorcha

La sexta opción del menú, **Piloto**, abre un juego de exploración de diez tableros. Toca casillas contiguas, recupera la reliquia y alcanza la salida antes de agotar la luz. Las llaves abren puertas y cada brasero recupera cinco unidades de luz una sola vez. El cofre es opcional; escapar, recoger el tesoro y alcanzar la ruta experta conceden hasta tres estrellas.

Todos los niveles están abiertos para pruebas, con reinicios e intentos ilimitados. Las estrellas y las mejores rutas se guardan localmente. Cada primera victoria recupera una reliquia para el campamento del piloto; las repeticiones no generan XP ni monedas en QuestDay. El botón Borrar marcas permite reiniciar únicamente el piloto.

Las pruebas del motor comprueban soluciones perfectas y su optimalidad por búsqueda exhaustiva, límites de movimiento, puertas/llaves, braseros de un uso y victoria con el último punto de luz.

## Estado y alcance

Versión **0.2.0 de prueba**, interfaz en español. No es todavía una edición de Google Play. Premium muestra su estado pendiente y no cobra: falta configurar el producto propio de QuestDay y conectar y probar Google Play Billing. Los idiomas adicionales y las ilustraciones finales personalizadas quedan pendientes. Los mundos usan paisajes vectoriales originales y los avatares usan los emojis del sistema.

## Compilar

JDK 17, Android SDK 36, Build Tools 36.0.0 y Gradle 8.13.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

El APK de prueba queda en `app/build/outputs/apk/debug/`. El flujo de GitHub Actions genera el mismo APK como artefacto. Las claves de firma y los datos privados no se incluyen en el repositorio. Para distribuir actualizaciones fuera de pruebas, crear y custodiar una clave de firma estable antes de publicar.

## Contacto

GQUEST · gquesteos@gmail.com
