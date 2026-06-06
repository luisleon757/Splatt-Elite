# Splatt Elite - Aplicación Android Nativa 🎯📱

Esta es la aplicación móvil oficial de **Splatt Elite** (entrenador de tiro virtual DIY), desarrollada de forma nativa utilizando **Kotlin** y **Jetpack Compose**. 

La aplicación se conecta a la placa Seeed Studio XIAO ESP32S3 Sense mediante **Bluetooth Low Energy (BLE)** para ofrecer visualización del disparo en tiempo real, calibración digital y control de la sesión de entrenamiento. Al usar BLE, se optimiza drásticamente el consumo, logrando una autonomía estimada de **3 a 4 horas continuas** con una batería de 400 mAh.

---

## 🚀 Características Principales

- **Detección de Disparo en Tiempo Real:** Dibuja sobre la diana olímpica oficial de la ISSF (escala milimétrica) el recorrido completo que realiza el láser IR previo a la detonación.
- **Puntuación Automática:** Muestra la puntuación calculada del último disparo (ej. 10.9, 9.5) basándose en las coordenadas del centroide del láser.
- **D-Pad de Calibración:** Permite ajustar milimétricamente el desfase de calibración del láser (`calib_x` y `calib_y`) de forma inalámbrica.
- **Asistente de Enfoque (Ciego):** Integra un medidor de nitidez en tiempo real transmitido vía BLE que asiste en el enfoque óptico de la lente M12 de la cámara.
- **Modo Carga / Bajo Consumo:** Envía comandos remotos de apagado temporal al ESP32 (`/sleep`) para entrar en Deep Sleep profundo y acelerar la carga de la batería.
- **Historial Completo:** Acceso directo con un solo toque al historial de disparos y sesiones almacenados en la tarjeta micro SD.
- **Paleta de Colores Moderna:** Interfaz con diseño premium en modo oscuro (o tema claro alternativo) y acentos naranja.

---

## 🛠️ Requisitos de Desarrollo

Para abrir, modificar o compilar este proyecto, necesitas:

1. **Android Studio** (Versión Ladybug o superior recomendada).
2. **Android SDK** para compilar con la versión 35 de la API.
3. **Java Development Kit (JDK) 17** o posterior (incluido de serie en Android Studio).

---

## 📂 Estructura del Proyecto

El código está estructurado de la siguiente forma:

- **[`MainActivity.kt`](file:///c:/Users/luis/.gemini/antigravity/playground/scatt_android/app/src/main/java/com/splatt/elite/MainActivity.kt):** Actividad principal que maneja los flujos de estado, los bucles de actualización periódicos (`LaunchedEffect` de polling) y la estructura de vistas Compose.
- **[`network/BleManager.kt`](file:///c:/Users/luis/.gemini/antigravity/playground/scatt_android/app/src/main/java/com/splatt/elite/network/BleManager.kt):** Gestor de conectividad **Bluetooth Low Energy (BLE)** encargado de descubrir, conectar y gestionar la comunicación bidireccional (comandos y notificaciones) con el firmware.
- **[`ui/components/TargetView.kt`](file:///c:/Users/luis/.gemini/antigravity/playground/scatt_android/app/src/main/java/com/splatt/elite/ui/components/TargetView.kt):** Lienzo dinámico (`Canvas`) que escala los milímetros físicos procedentes de la cámara al espacio de píxeles en pantalla usando el factor de aumento seleccionado y dibuja tanto la trayectoria del láser como los disparos.
- **[`ui/components/SettingsDialog.kt`](file:///c:/Users/luis/.gemini/antigravity/playground/scatt_android/app/src/main/java/com/splatt/elite/ui/components/SettingsDialog.kt):** Cuadro de configuración para modificar exposición, ganancia, filtros de sensibilidad al láser e intensidad acústica para el micrófono PDM.
- **[`ui/components/FocusDialog.kt`](file:///c:/Users/luis/.gemini/antigravity/playground/scatt_android/app/src/main/java/com/splatt/elite/ui/components/FocusDialog.kt):** Reproductor a tiempo real de la señal del visor para calibrar el enfoque de lente.

---

## 🔗 Integración con el Hardware (ESP32)

La app se comunica mediante **Bluetooth Low Energy (BLE)** recibiendo notificaciones periódicas (JSON) y enviando comandos cortos:
- Notificaciones: Recepción de JSON constante con el estado de captura, coordenadas del láser (`x`, `y`) y métricas (`time`, `v`).
- `start_shot` / `cancel_shot`: Activan o desactivan la espera de detonación.
- `start_calib` / `stop_calib`: Activan o desactivan la traza del láser para calibración.
- `sleep`: Pone a la placa en Deep Sleep (Modo Carga) cortando el consumo.
- Ajustes rápidos (ej. `exp:300`, `gain:0`, `thr:10`): Modifican los registros del sensor en tiempo real.

---

## 📤 Instrucciones para Git y GitHub

Dado que ya se ha inicializado el repositorio local con un commit de los archivos base, puedes conectar este proyecto a tu cuenta de GitHub siguiendo estos pasos en tu terminal (dentro de la carpeta `scatt_android`):

1. **Crea un repositorio vacío** en GitHub (sin añadir README ni `.gitignore` para evitar conflictos).
2. **Asocia y sube el código** ejecutando:
   ```bash
   git remote add origin https://github.com/tu-usuario/nombre-del-repositorio.git
   git branch -M main
   git push -u origin main
   ```
