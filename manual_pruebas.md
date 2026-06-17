# 🎯 Splatt Elite: Manual de Usuario y Plan de Pruebas

Bienvenido al manual y plan de validación de **Splatt Elite**, un sistema avanzado de telemetría y entrenamiento para tiro deportivo basado en visión artificial y acelerometría.

Este documento está diseñado para que cualquier persona, independientemente de su familiaridad técnica con el proyecto, pueda entender cómo funciona el sistema y realizar una prueba completa de todas sus capacidades en galería o en seco.

---

## 📖 PARTE 1: Conocimiento del Producto

**Splatt Elite** se compone de dos partes:
1.  **Hardware (Cámara + Sensor):** Un dispositivo (ESP32-S3) con una cámara y un sensor de movimiento que se acopla al arma.
2.  **Aplicación Móvil:** Una app para Android que se conecta por Bluetooth (BLE) al hardware para procesar la imagen, registrar los disparos y mostrar estadísticas.

### Principales Funcionalidades

*   **Detección Óptica:** La cámara mira a través del visor o hacia la diana y es capaz de reconocer el centro negro de la misma para calcular el punto de impacto exacto.
*   **Auto-Ajuste Visual ("Auto-Tune"):** El sistema ajusta automáticamente la sensibilidad de luz de la cámara al pulsar "Calibrar" para asegurar que ve la diana nítidamente, ignorando los cambios bruscos de iluminación de las galerías.
*   **Traza Pre y Post Disparo:** Gracias a los sensores inerciales, la app dibuja la trayectoria del arma antes (color verde/azul/amarillo) y después (rojo) del disparo, permitiendo analizar la "parada".
*   **Dictado por Voz (TTS):** Al producirse el disparo, la app lee en voz alta la puntuación y el porcentaje de estabilidad, para que el tirador no tenga que apartar la vista del arma.
*   **Historial CSV Transparente:** La app guarda silenciosamente cada disparo de tu sesión. Puedes acceder al historial en cualquier momento y abrirlo directamente en Excel para análisis profundos.

---

## 🧪 PARTE 2: Plan de Pruebas de Campo

El siguiente protocolo de pruebas debe ejecutarse paso a paso en una galería de tiro (o en seco apuntando a una diana impresa a la distancia correcta).

### Prueba 1: Conexión y Arranque Inicial
> [!NOTE]
> *Objetivo:* Verificar que la aplicación encuentra el dispositivo y negocia los parámetros iniciales.
1. Enciende el hardware Splatt Elite.
2. Abre la aplicación de Android y concédele los permisos de Bluetooth y Ubicación.
3. Observa el estado en la esquina superior izquierda. Debe pasar de **Desconectado** a **Conectado** en pocos segundos.
4. **Verificación:** Al conectarse, la cámara empezará a transmitir datos. Podrás ver valores de aceleración y un dibujo inicial en la pantalla de trazado.

### Prueba 2: Auto-Tune y Calibración
> [!IMPORTANT]
> *Objetivo:* Validar que el algoritmo de luz es capaz de adaptar la cámara a la iluminación de la diana.
1. Adopta tu posición de tiro natural y apunta directamente al centro de la diana.
2. Con el arma lo más quieta posible apuntando al centro, pide a un compañero que pulse el botón **"CALIBRAR"** en la app.
3. La app mostrará el mensaje *"Auto-Ajustando Visión... Mantén el arma firme"*. 
4. **Verificación:** Durante ~1.5 segundos, la traza se quedará quieta (la cámara está haciendo un barrido rápido de iluminación).
5. Pasado ese tiempo, la app pasará a estado de lectura normal. Apunta y mueve el arma ligeramente. Comprueba que en la zona superior de la pantalla el indicador *"Sens:"* (o la lectura de luz) se ha ajustado, y que la traza verde se mueve respondiendo a tus movimientos sobre la diana.

### Prueba 3: Detección y Dictado del Disparo
> [!TIP]
> *Objetivo:* Asegurar que la traza y el micrófono/vibración reconocen el evento del gatillo.
1. Asegúrate de tener el volumen multimedia del móvil activado.
2. Realiza un disparo real (o en seco).
3. **Verificación:** 
    * La aplicación debe marcar el impacto con una cruz en la pantalla.
    * La traza posterior al impacto debe pintarse de color **rojo**.
    * Una voz sintética debe dictarte el resultado: *"Nueve con cuatro... parada sesenta y cinco por ciento"*.
4. Realiza una serie de 5 disparos consecutivos y comprueba que todos se registran en la lista de la izquierda.

### Prueba 4: Exportación y Gestión de Historial
> [!NOTE]
> *Objetivo:* Probar el sistema de guardado continuo sin pérdida de datos.
1. Sin darle a borrar, pulsa el botón verde **"📥 CSV"**.
2. Deberá abrirse una ventana modal de **"Historial de Sesiones"**.
3. En la lista, verás la sesión actual (la fecha y hora actual).
4. Pulsa el botón **"Abrir"** de esa sesión.
5. **Verificación:** Debe saltar la app de Excel (u otra de hojas de cálculo) mostrando los datos perfectamente tabulados con tus últimos 5 disparos (Puntuación, Tiempo de apuntado, Parada, etc.).
6. Vuelve a la app Splatt y pulsa el botón rojo **"Borrar"**.
7. Realiza 2 disparos nuevos.
8. Vuelve al menú **"📥 CSV"**.
9. **Verificación:** Ahora deberías ver **dos** sesiones en el historial: la antigua de 5 disparos y una nueva, creada automáticamente al empezar de cero.

### Prueba 5: Estadísticas y Ajustes
> [!TIP]
> *Objetivo:* Comprobar la usabilidad de las herramientas analíticas.
1. Pulsa el botón azul **"📊 Stats"**.
2. **Verificación:** Debería aparecer una pantalla con la información agrupada y un análisis básico de tu consistencia.
3. Cierra Stats y pulsa **"⚙️ Ajustes"**.
4. Modifica parámetros como la Sensibilidad Acústica (si el disparo en seco no lo detecta bien) o la Distancia de Tiro. Dale a Guardar.
5. Realiza un último disparo para confirmar que los nuevos valores afectan al cálculo.
