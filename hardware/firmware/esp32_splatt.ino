#include "esp_camera.h"
#include "img_converters.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ESP_I2S.h>
#include <Wire.h>

I2SClass I2S;

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
// MICRÓFONO DIGITAL (I2S - PDM XIAO SENSE)
// ==========================================
#define I2S_PORT I2S_NUM_0
#define I2S_WS 42  // CLK
#define I2S_SD 41  // DAT

// Variables Volátiles para la Interrupción/Tarea
volatile bool shotDetected = false;
volatile unsigned long lastShotTime = 0;

// Estado Continuo
bool has_shot = false;
float current_x = 160.0;
float current_y = 120.0;
int current_v = 0;
int focus_value = 0;
unsigned long last_laser_seen_time = 0;

// Estado del Sistema (Máquina de Estados)
enum SystemState {
  STATE_STANDBY,
  STATE_AIMING,
  STATE_POST_SHOT,
  STATE_FOCUSING
};
volatile SystemState currentState = STATE_STANDBY;

unsigned long aim_start_time = 0;
unsigned long post_shot_start_time = 0;
float shot_x = 0.0;
float shot_y = 0.0;
unsigned long aim_duration_ms = 0;
bool isCalibratingServer = false;

// Configuración de Ajustes de Detección IR
int detect_threshold = 40; 
int cam_exposure = 300;     
int cam_gain = 0;           
int audio_threshold = 2000; 
int max_audio_threshold = 60000; 

// ==========================================
// TAREA FREERTOS: LECTURA DE AUDIO I2S
// ==========================================
void i2sAudioTask(void *pvParameters) {
  while (1) {
    if (currentState != STATE_AIMING) {
      vTaskDelay(100 / portTICK_PERIOD_MS);
      continue;
    }
    int32_t audio_energy = 0;
    int samples_read = 0;
    int16_t min_sample = 32767;
    int16_t max_sample = -32768;
    
    for (int i = 0; i < 128; i++) {
      int sample = I2S.read();
      if (sample != -1 && sample != 0) {
        int16_t val = (int16_t)sample;
        if (val < min_sample) min_sample = val;
        if (val > max_sample) max_sample = val;
        samples_read++;
      }
    }
    
    if (samples_read > 0 && max_sample >= min_sample) {
      audio_energy = max_sample - min_sample;
    }
    
    if (audio_energy > audio_threshold && audio_energy < max_audio_threshold) {
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
    vTaskDelay(10 / portTICK_PERIOD_MS); 
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
          currentState = STATE_AIMING;
          shotDetected = false;
          has_shot = false;
        } else if (cmd == "stop_calib") {
          isCalibratingServer = false;
          currentState = STATE_STANDBY;
          has_shot = false;
        } else if (cmd == "sleep") {
          esp_deep_sleep_start();
        } else if (cmd == "start_focus") {
          currentState = STATE_FOCUSING;
        } else if (cmd == "stop_focus") {
          currentState = STATE_STANDBY;
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

  // Configuración del Micrófono (PDM - XIAO SENSE)
  I2S.setPinsPdmRx(42, 41);
  if (!I2S.begin(I2S_MODE_PDM_RX, 16000, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO)) {
    Serial.println("Error: Fallo al inicializar el micrófono I2S.");
  } else {
    Serial.println("Micrófono I2S Inicializado OK");
  }

  xTaskCreatePinnedToCore(i2sAudioTask, "AudioTask", 4096, NULL, 1, NULL, 1);
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
      (current_v > detect_threshold) ? current_x : 0.0f,
      (current_v > detect_threshold) ? current_y : 0.0f,
      (current_v > detect_threshold) ? current_v : 0,
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
      if (current_v > detect_threshold || (millis() - last_laser_seen_time < 250)) {
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
    if (currentState == STATE_FOCUSING) {
      // Calcular la nitidez (Laplaciano simplificado) de toda la imagen
      long sharpness = 0;
      
      // Muestrear toda la imagen pero saltando pixeles para hacerlo muy rápido
      // ya que el ESP32 tiene que mantener los FPS altos.
      for (int y = 1; y < fb->height; y += 2) {
        for (int x = 1; x < fb->width; x += 2) {
          int idx = y * fb->width + x;
          int val = fb->buf[idx];
          
          int prev_x = fb->buf[idx - 1];
          int prev_y = fb->buf[idx - fb->width];
          
          int diff_x = val - prev_x;
          int diff_y = val - prev_y;
          
          // Ignorar ruido muy bajo, pero aceptar gradientes suaves
          if (abs(diff_x) > 2) sharpness += abs(diff_x);
          if (abs(diff_y) > 2) sharpness += abs(diff_y);
        }
      }
      
      long raw_focus = sharpness / 10;
      
      // Suavizado exponencial (Filtro pasa bajos) para evitar temblores
      static float smoothed_focus = -1.0f;
      if (smoothed_focus < 0) smoothed_focus = raw_focus; // inicializar
      smoothed_focus = (smoothed_focus * 0.85f) + (raw_focus * 0.15f);
      
      focus_value = (int)smoothed_focus;

    } else {
      // Detección normal de láser
      int max_val = 0;
      for (int i = 0; i < fb->width * fb->height; i++) {
        uint8_t val = fb->buf[i];
        if (val > max_val) {
          max_val = val;
        }
      }

      if (max_val > detect_threshold) {
        long sum_x = 0;
        long sum_y = 0;
        int count = 0;
        int threshold = max_val - 20; 
        if (threshold < detect_threshold) threshold = detect_threshold;

        for (int y = 0; y < fb->height; y++) {
          for (int x = 0; x < fb->width; x++) {
            int idx = y * fb->width + x;
            uint8_t val = fb->buf[idx];
            if (val >= threshold) {
              sum_x += x;
              sum_y += y;
              count++;
            }
          }
        }

        if (count > 0) {
          current_x = (float)sum_x / count;
          current_y = (float)sum_y / count;
          current_v = max_val;
          last_laser_seen_time = millis();
        }
      } else {
        current_v = 0;
      }
    }
    
    esp_camera_fb_return(fb);
  }
}
