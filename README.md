# Splatt Elite - Aplicación Android Nativa 🎯📱

Esta es la aplicación móvil oficial de **Splatt Elite** (entrenador de tiro virtual DIY), desarrollada de forma nativa utilizando **Kotlin** y **Jetpack Compose**. 

La aplicación se conecta a la placa Seeed Studio XIAO ESP32S3 Sense mediante **Bluetooth Low Energy (BLE)** para ofrecer visualización del disparo en tiempo real, calibración digital y control de la sesión de entrenamiento. Al usar BLE, se optimiza drásticamente el consumo, logrando una autonomía estimada de **3 a 4 horas continuas** con una batería de 400 mAh.

---

## 🚀 Características Principales

- **Detección de Disparo en Tiempo Real:** Dibuja sobre la diana olímpica oficial de la ISSF (escala milimétrica) el recorrido completo que realiza el arma apuntando a la diana previo a la detonación.
- **Puntuación Automática:** Muestra la puntuación calculada del último disparo (ej. 10.9, 9.5) basándose en las coordenadas exactas del impacto registradas por la cámara.
- **D-Pad de Calibración:** Permite ajustar milimétricamente el desfase de calibración óptica (`calib_x` y `calib_y`) de forma inalámbrica.
- **Asistente de Enfoque:** Integra una vista o medidor para el enfoque óptico de la lente M12 de la cámara de forma inalámbrica.
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

## 📂 Estructura del Proyecto (Monorepo)

Este repositorio es un **Monorepo** que contiene tanto el código de la aplicación móvil como el software de la placa (ESP32) y los diseños 3D. Se divide principalmente en dos grandes bloques:

### 📱 1. Aplicación Android (`/app`)
La raíz del proyecto funciona como un proyecto estándar de Android Studio. El código fuente de la app se encuentra en la carpeta `app/src/main/java/com/splatt/elite/`:
- **`MainActivity.kt`:** Interfaz principal en Jetpack Compose.
- **`network/BleManager.kt`:** Gestor de conectividad **BLE** para hablar con el firmware.
- **`ui/components/TargetView.kt`:** Lienzo dinámico (`Canvas`) que dibuja la diana a escala milimétrica y la trayectoria del arma.

### 🔌 2. Hardware y Arduino (`/hardware`)
Todo lo relacionado con la construcción física del dispositivo y el código del microcontrolador se encuentra aislado en la carpeta `hardware/`:
- **`hardware/firmware/`**: Aquí está el código de **Arduino** (`esp32_splatt.ino`, `camera_pins.h`) que debes subir a la placa Seeed Studio XIAO ESP32S3 Sense.
- **`hardware/3D_Models/`**: Archivos `.stl` listos para imprimir la carcasa protectora en 3D.
- **`hardware/Docs/`**: Guía de uso, lista de materiales para construirlo (`BOM.md`) y todas las **Dianas oficiales para imprimir** (`Targets/`).

---

## 🔗 Integración con el Hardware (ESP32)

La app se comunica mediante **Bluetooth Low Energy (BLE)** recibiendo notificaciones periódicas (JSON) y enviando comandos cortos:
- Notificaciones: Recepción de JSON constante con el estado de captura, coordenadas de apunte (`x`, `y`) y métricas (`time`, `v`).
- `start_shot` / `cancel_shot`: Activan o desactivan la espera de detonación.
- `start_calib` / `stop_calib`: Activan o desactivan la traza visual para calibración.
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
