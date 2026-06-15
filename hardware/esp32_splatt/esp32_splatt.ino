#include "esp_camera.h"
#include "img_converters.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Wire.h>

// ==========================================
// CONFIGURACIÓN BLE
// ==========================================
#define BLE_DEVICE_NAME "Splatt_Elite"
#define SERVICE_UUID           "12345678-1234-5678-1234-56789abcdef0"
#define STATUS_CHAR_UUID       "12345678-1234-5678-1234-56789abcdef1"
#define COMMAND_CHAR_UUID      "12345678-1234-5678-1234-56789abcdef2"
#define CONFIG_CHAR_UUID       "12345678-1234-5678-1234-56789abcdef3"

BLEServer* pServer = NULL;
BLECharacteristic* pStatusChar = NULL;
bool deviceConnected = false;

// ==========================================
// PINES DE LA CAMARA (ESP32-S3-CAM-OV3660 genérico)
// ==========================================
#define PWDN_GPIO_NUM    -1
#define RESET_GPIO_NUM   -1
#define XCLK_GPIO_NUM    10

#define SIOD_GPIO_NUM    40
#define SIOC_GPIO_NUM    39

#define Y9_GPIO_NUM      48
#define Y8_GPIO_NUM      11
#define Y7_GPIO_NUM      12
#define Y6_GPIO_NUM      14
#define Y5_GPIO_NUM      16
#define Y4_GPIO_NUM      18
#define Y3_GPIO_NUM      17
#define Y2_GPIO_NUM      15

#define VSYNC_GPIO_NUM   38
#define HREF_GPIO_NUM    47
#define PCLK_GPIO_NUM    13

// ==========================================
// MPU6050 (HW-123) ACELERÓMETRO/INCLINÓMETRO
// ==========================================
#define I2C_SDA_PIN 5
#define I2C_SCL_PIN 6
#define IMU_INT_PIN 4

#define MPU6050_ADDR         0x68
#define MPU6050_ACCEL_CONFIG 0x1C
#define MPU6050_MOT_THR      0x1F
#define MPU6050_MOT_DUR      0x20
#define MPU6050_INT_PIN_CFG  0x37
#define MPU6050_INT_ENABLE   0x38
#define MPU6050_INT_STATUS   0x3A
#define MPU6050_ACCEL_XOUT_H 0x3B
#define MPU6050_PWR_MGMT_1   0x6B

// Variables Volátiles para la Interrupción/Tarea
volatile bool shotDetected = false;
volatile unsigned long lastShotTime = 0;

// Estado Continuo
bool has_shot = false;
float current_x = 160.0;
float current_y = 120.0;
int current_v = 0;
unsigned long last_debug_print = 0; // Global variable for debug timing
int focus_value = 0;
unsigned long last_laser_seen_time = 0;

// Estado del Sistema (Máquina de Estados)
enum SystemState {
  STATE_STANDBY,
  STATE_AIMING,
  STATE_POST_SHOT
};
volatile SystemState currentState = STATE_STANDBY;

unsigned long aim_start_time = 0;
unsigned long post_shot_start_time = 0;
float shot_x = 0.0;
float shot_y = 0.0;
unsigned long aim_duration_ms = 0;
bool isCalibratingServer = false;

// Configuración de Ajustes de Detección IR
int detect_threshold = 80; 
int cam_exposure = 300;     
int cam_gain = 0;           
int audio_threshold = 2000; 
int max_audio_threshold = 60000; 

void updateMpuThreshold(int ui_value) {
  // Mapeamos ui_value (250 a 2500) a un umbral del MPU6050
  // Para el usuario, 10 (ui_value=2500) es muy sensible -> MPU_THR muy bajo
  // Para el usuario, 1 (ui_value=250) es poco sensible -> MPU_THR alto
  uint8_t mpu_thr = map(ui_value, 250, 2500, 25, 2);
  Wire.beginTransmission(MPU6050_ADDR);
  Wire.write(MPU6050_MOT_THR);
  Wire.write(mpu_thr);
  Wire.endTransmission();
}

// ==========================================
// INTERRUPCIÓN HARDWARE (GOLPE DETECTADO)
// ==========================================
void IRAM_ATTR detectShock() {
  if (currentState == STATE_AIMING) {
    unsigned long currentTime = millis();
    if (currentTime - lastShotTime > 5000) {
      if (lastShotTime == 0 && currentTime < 5000) {
        lastShotTime = currentTime;
      } else {
        shotDetected = true;
        lastShotTime = currentTime;
      }
    }
  }
}

// ==========================================
// TAREA FREERTOS: INCLINÓMETRO Y ESTADO MPU
// ==========================================
void imuTask(void *pvParameters) {
  while (1) {
    if (currentState == STATE_AIMING || currentState == STATE_STANDBY) {
      // Leer Acelerómetro para Inclinómetro (Pitch)
      Wire.beginTransmission(MPU6050_ADDR);
      Wire.write(MPU6050_ACCEL_XOUT_H);
      Wire.endTransmission(false);
      Wire.requestFrom(MPU6050_ADDR, 6, true);
      
      if (Wire.available() == 6) {
        int16_t ax = Wire.read() << 8 | Wire.read();
        int16_t ay = Wire.read() << 8 | Wire.read();
        int16_t az = Wire.read() << 8 | Wire.read();
        
        // Calculamos la inclinación (Pitch) para montaje VERTICAL (Placa en la cara trasera del arma)
        // El eje Z apunta hacia ti (horizontal). La gravedad recae en X e Y.
        float pitch = atan2((float)az, sqrt((float)ax*ax + (float)ay*ay)) * 180.0 / PI;
        
        // Calculamos la inclinación lateral (Roll) para detectar si se apoya en la mesa
        float roll = atan2((float)ax, (float)ay) * 180.0 / PI;
        if (roll > 90.0) roll -= 180.0;
        if (roll < -90.0) roll += 180.0;
        
        if (currentState == STATE_STANDBY) {
          if (abs(pitch) < 20.0 && abs(roll) < 60.0) { // Arma horizontal y sin ladear demasiado
            currentState = STATE_AIMING;
            shotDetected = false;
            aim_start_time = millis();
            Serial.println("INCLINOMETRO: Arma levantada y recta. Modo APUNTANDO.");
          }
        } else if (currentState == STATE_AIMING) {
          if (abs(pitch) > 30.0 || abs(roll) > 60.0) { // Arma bajada o ladeada (en la mesa)
            currentState = STATE_STANDBY;
            has_shot = false;
            if (abs(roll) > 60.0) {
              Serial.println("INCLINOMETRO: Arma ladeada. Modo STANDBY.");
            } else {
              Serial.println("INCLINOMETRO: Arma bajada. Modo STANDBY.");
            }
          }
        }
      }
      
      // Limpiar el registro de interrupciones del MPU6050
      Wire.beginTransmission(MPU6050_ADDR);
      Wire.write(MPU6050_INT_STATUS);
      Wire.endTransmission(false);
      Wire.requestFrom(MPU6050_ADDR, 1, true);
      if (Wire.available()) { Wire.read(); }
    }
    vTaskDelay(100 / portTICK_PERIOD_MS); // 10Hz es suficiente para el Inclinómetro
  }
}

bool camera_ok = false;

// ==========================================
// CALLBACKS BLE
// ==========================================
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("BLE Conectado");
    };
    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println("BLE Desconectado");
      pServer->startAdvertising();
    }
};

class MyCommandCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String rxValue = pCharacteristic->getValue();
      if (rxValue.length() > 0) {
        String cmd = rxValue;
        Serial.print("Comando BLE: "); Serial.println(cmd);
        
        if (cmd == "start_shot" && currentState == STATE_STANDBY) {
          currentState = STATE_AIMING;
          shotDetected = false;
          aim_start_time = millis();
        } else if (cmd == "cancel_shot") {
          currentState = STATE_STANDBY;
          has_shot = false;
        } else if (cmd == "start_calib") {
          isCalibratingServer = true;
          // No forzamos currentState = STATE_AIMING aquí, 
          // permitimos que el inclinómetro lo levante naturalmente.
          shotDetected = false;
          has_shot = false;
        } else if (cmd == "stop_calib") {
          isCalibratingServer = false;
          currentState = STATE_STANDBY;
          has_shot = false;
        } else if (cmd == "sleep") {
          esp_deep_sleep_start();
        }
      }
    }
};

class MyConfigCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String rxValue = pCharacteristic->getValue();
      String config = rxValue;
      Serial.print("Config BLE: "); Serial.println(config);
      
      if (config.startsWith("thr:")) detect_threshold = config.substring(4).toInt();
      else if (config.startsWith("exp:")) {
        cam_exposure = config.substring(4).toInt();
        sensor_t * s = esp_camera_sensor_get();
        if(s) s->set_aec_value(s, cam_exposure);
      }
      else if (config.startsWith("gain:")) {
        cam_gain = config.substring(5).toInt();
        sensor_t * s = esp_camera_sensor_get();
        if(s) s->set_agc_gain(s, cam_gain);
      }
      else if (config.startsWith("snd:")) {
        audio_threshold = config.substring(4).toInt();
        updateMpuThreshold(audio_threshold);
      }
      else if (config.startsWith("max_snd:")) {
        max_audio_threshold = config.substring(8).toInt();
      }
    }
};

// ==========================================
// SETUP ROBUSTO
// ==========================================
void setup() {
  Serial.begin(115200);
  delay(1000); 
  Serial.println("\n--- Arranque Splatt Elite S3 (BLE) ---");

  // Inicializar BLE
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(512); // Aumentar MTU para evitar truncamiento del JSON
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pStatusChar = pService->createCharacteristic(
                      STATUS_CHAR_UUID,
                      BLECharacteristic::PROPERTY_READ   |
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pStatusChar->addDescriptor(new BLE2902());

  BLECharacteristic *pCommandChar = pService->createCharacteristic(
                                         COMMAND_CHAR_UUID,
                                         BLECharacteristic::PROPERTY_WRITE
                                       );
  pCommandChar->setCallbacks(new MyCommandCallbacks());

  BLECharacteristic *pConfigChar = pService->createCharacteristic(
                                         CONFIG_CHAR_UUID,
                                         BLECharacteristic::PROPERTY_WRITE
                                       );
  pConfigChar->setCallbacks(new MyConfigCallbacks());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.println("BLE Advertising Iniciado");

  // Configuración de la Cámara ESP32-S3
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;

  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;

  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;

  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;

  config.pin_pwdn  = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;

  config.xclk_freq_hz = 10000000;
  // Usar GRAYSCALE para ahorrar memoria y procesamiento (1 byte por pixel en vez de 2)
  config.pixel_format = PIXFORMAT_GRAYSCALE; 

  config.frame_size = FRAMESIZE_QVGA;
  config.jpeg_quality = 15;

  config.fb_count = 1;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;

  if(esp_camera_init(&config) != ESP_OK) {
    Serial.println("Fallo Grave Camara");
    camera_ok = false;
  } else {
    Serial.println("Camara OK");
    camera_ok = true;
    
    sensor_t * s = esp_camera_sensor_get();
    if(s) {
      s->set_exposure_ctrl(s, 0); 
      s->set_aec2(s, 0);
      s->set_aec_value(s, cam_exposure);
      s->set_gain_ctrl(s, 0); 
      s->set_agc_gain(s, cam_gain);
    }
  }

  // Configuración del MPU6050 (I2C)
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  Wire.setClock(400000); // 400kHz I2C

  // Despertar MPU6050
  Wire.beginTransmission(MPU6050_ADDR);
  Wire.write(MPU6050_PWR_MGMT_1);
  Wire.write(0x00);
  if(Wire.endTransmission() == 0) {
    Serial.println("MPU6050 (HW-123) Inicializado OK");
    
    // Configurar Acelerómetro (+/- 8g)
    Wire.beginTransmission(MPU6050_ADDR);
    Wire.write(MPU6050_ACCEL_CONFIG);
    Wire.write(0x10); 
    Wire.endTransmission();
    
    // Configurar Duración de Interrupción de Movimiento
    Wire.beginTransmission(MPU6050_ADDR);
    Wire.write(MPU6050_MOT_DUR);
    Wire.write(1); // 1 ms
    Wire.endTransmission();
    
    // Configurar Umbral Inicial
    updateMpuThreshold(audio_threshold);
    
    // Activar Interrupción de Movimiento
    Wire.beginTransmission(MPU6050_ADDR);
    Wire.write(MPU6050_INT_ENABLE);
    Wire.write(0x40); // MOT_EN
    Wire.endTransmission();
    
    // Configurar pin INT (Push-Pull, Active High)
    Wire.beginTransmission(MPU6050_ADDR);
    Wire.write(MPU6050_INT_PIN_CFG);
    Wire.write(0x00);
    Wire.endTransmission();
    
    // Configurar Interrupción en ESP32
    pinMode(IMU_INT_PIN, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(IMU_INT_PIN), detectShock, RISING);
    
    xTaskCreatePinnedToCore(imuTask, "IMUTask", 4096, NULL, 1, NULL, 1);
  } else {
    Serial.println("Error: Fallo al comunicarse con MPU6050 por I2C.");
  }
}

// ==========================================
// LOOP PRINCIPAL RAPIDO (VISIÓN)
// ==========================================
unsigned long last_notify_time = 0;

void loop() {
  // Notificar estado a través de BLE a 10Hz
  if (deviceConnected && millis() - last_notify_time > 100) {
    char json[200];
    snprintf(json, sizeof(json), 
      "{\"state\":%d,\"shot_x\":%.2f,\"shot_y\":%.2f,\"time\":%lu,\"x\":%.2f,\"y\":%.2f,\"v\":%d,\"s\":%d,\"c\":%d,\"f\":%d}",
      (int)currentState, shot_x, shot_y, 
      (currentState == STATE_AIMING) ? (millis() - aim_start_time) : aim_duration_ms,
      (current_v > 0) ? current_x : 0.0f,
      (current_v > 0) ? current_y : 0.0f,
      (current_v > 0) ? current_v : 0,
      (has_shot ? 1 : 0), (camera_ok ? 1 : 0), focus_value
    );
    pStatusChar->setValue((uint8_t*)json, strlen(json));
    pStatusChar->notify();
    last_notify_time = millis();

    if (isCalibratingServer && has_shot) {
      has_shot = false;
    }
  }

  if (currentState == STATE_STANDBY) {
    delay(50);
    return;
  }

  if (currentState == STATE_AIMING) {
    if (shotDetected) {
      if (current_v > 0 || (millis() - last_laser_seen_time < 250)) {
        shot_x = current_x;
        shot_y = current_y;
        
        if (isCalibratingServer) {
          shotDetected = false;
          has_shot = true;
          Serial.println("BANG! Disparo de calibración registrado.");
        } else {
          aim_duration_ms = millis() - aim_start_time;
          shotDetected = false;
          has_shot = true;
          currentState = STATE_POST_SHOT;
          post_shot_start_time = millis();
          Serial.println("BANG! Disparo de entrenamiento detectado.");
        }
      } else if (millis() - lastShotTime > 250) {
        shotDetected = false;
        Serial.println("Ruido externo o disparo ignorado.");
      }
    }
  }

  if (currentState == STATE_POST_SHOT) {
    if (millis() - post_shot_start_time >= 5000) {
      currentState = STATE_STANDBY;
      has_shot = false;
      Serial.println("Volviendo a modo STANDBY.");
    }
  }

  camera_fb_t * fb = esp_camera_fb_get();
  if (fb) {
      // Detección de diana pasiva (bloque oscuro)
      int min_val = 255;
      int min_x = 0;
      int min_y = 0;
      
      // Muestreo rápido para encontrar el área más oscura (saltando bordes para evitar sombras de viñeteo)
      for (int y = 5; y < fb->height - 5; y++) {
        for (int x = 5; x < fb->width - 5; x++) {
          int idx = y * fb->width + x;
          uint8_t val = fb->buf[idx];
          if (val < min_val) {
            min_val = val;
            min_x = x;
            min_y = y;
          }
        }
      }

      // El umbral dinámico será un poco más claro que el punto más oscuro para coger toda la mancha
      int dark_tolerance = min_val + 30; 
      
      // Validamos que la zona sea lo suficientemente oscura según el detect_threshold enviado desde la app
      // Usamos solo min_val para que sea más fácil de calibrar con el slider de la app
      if (min_val <= detect_threshold) {
        long sum_x = 0;
        long sum_y = 0;
        int count = 0;
        
        // Búsqueda en una ventana local muy amplia (+/- 80 píxeles) para asegurar que cogemos TODA la diana negra
        // Si la ventana es muy pequeña y la diana se ve muy grande, el cálculo se queda atascado en el centro.
        int radius = 80;
        int start_x = (min_x - radius > 0) ? (min_x - radius) : 0;
        int end_x = (min_x + radius < fb->width - 1) ? (min_x + radius) : (fb->width - 1);
        int start_y = (min_y - radius > 0) ? (min_y - radius) : 0;
        int end_y = (min_y + radius < fb->height - 1) ? (min_y + radius) : (fb->height - 1);

        for (int y = start_y; y <= end_y; y++) {
          for (int x = start_x; x <= end_x; x++) {
            int idx = y * fb->width + x;
            uint8_t val = fb->buf[idx];
            // Si el píxel es negro (o muy oscuro), lo sumamos al centro de masa
            if (val <= dark_tolerance) {
              sum_x += x;
              sum_y += y;
              count++;
            }
          }
        }

        // Filtramos para asegurar que no sea solo un pixel muerto, sino una mancha real
        if (count > 10) {
          // INVERSIÓN DE EJES (320 - x, 240 - y):
          // Al mover la pistola a la derecha, la cámara ve que la diana se mueve a la izquierda.
          // Invertimos los ejes aquí para que la App de Android lo dibuje correctamente.
          float raw_x = 320.0f - ((float)sum_x / count);
          float raw_y = 240.0f - ((float)sum_y / count);
          
          // Suavizado (Media móvil exponencial) para mitigar el Rolling Shutter
          if (current_v == 0) { 
            current_x = raw_x;
            current_y = raw_y;
          } else {
            current_x = (current_x * 0.6f) + (raw_x * 0.4f);
            current_y = (current_y * 0.6f) + (raw_y * 0.4f);
          }
          
          current_v = 255 - min_val; // Invertimos para que la app vea un valor alto cuando es correcto
          last_laser_seen_time = millis();
        } else {
           current_v = 0;
        }
      } else {
        current_v = 0;
      }

      // Print debug info to Serial Monitor every 1 second
      if (millis() - last_debug_print > 1000) {
        if (current_v > 0) {
          Serial.print("DIANA DETECTADA -> Oscuridad: ");
          Serial.print(current_v);
          Serial.print(" | Pos X: ");
          Serial.print(current_x);
          Serial.print(" | Pos Y: ");
          Serial.println(current_y);
        } else {
          Serial.print("BUSCANDO DIANA -> Pto mas oscuro: ");
          Serial.println(min_val);
        }
        last_debug_print = millis();
      }
      
    esp_camera_fb_return(fb);
  }
}
