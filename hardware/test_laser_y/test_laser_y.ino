#include "esp_camera.h"

// PINES DE LA CAMARA (ESP32-S3-CAM-OV3660 genérico / XIAO SENSE)
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

void setup() {
  Serial.begin(115200);
  delay(2000); // Esperar un poco a que se abra el monitor serie
  
  Serial.println("Inicializando cámara...");

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
  config.pixel_format = PIXFORMAT_GRAYSCALE; 
  config.frame_size = FRAMESIZE_QVGA; // 320x240
  config.jpeg_quality = 15;
  config.fb_count = 1;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;

  if(esp_camera_init(&config) != ESP_OK) {
    Serial.println("Fallo al iniciar Camara");
    while(1) delay(1000);
  }

  // Desactivar ajuste automático para ver valores reales sin interferencia
  sensor_t * s = esp_camera_sensor_get();
  if(s) {
    s->set_exposure_ctrl(s, 0); 
    s->set_aec2(s, 0);
    s->set_aec_value(s, 50); // Exposición corta para el láser
    s->set_gain_ctrl(s, 0); 
    s->set_agc_gain(s, 0);
  }
  
  Serial.println("--- LISTO PARA MEDIR ---");
  Serial.println("Por favor, mueve el laser formando un cuadrado o cruz (arriba, abajo, izquierda, derecha).");
  Serial.println("Formato de salida CSV para analisis:");
  Serial.println("X,Y,Brillo");
}

void loop() {
  camera_fb_t * fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println("Frame buffer fail");
    delay(100);
    return;
  }

  int max_val = 0;
  int max_y = 0;
  int max_x = 0;

  for (int y = 0; y < fb->height; y++) {
    for (int x = 0; x < fb->width; x++) {
      int idx = y * fb->width + x;
      uint8_t val = fb->buf[idx];
      if (val > max_val) {
        max_val = val;
        max_x = x;
        max_y = y;
      }
    }
  }

  if (max_val > 40) { // Umbral mínimo de detección
    // Imprimir en formato CSV
    Serial.print(max_x);
    Serial.print(",");
    Serial.print(max_y);
    Serial.print(",");
    Serial.println(max_val);
  }

  esp_camera_fb_return(fb);
  
  // Imprimir más rápido (cada 20ms) para tener muchos datos
  delay(20);
}
