#include "esp_camera.h"
#include <WiFi.h>
#include "esp_http_server.h"

// ==========================================
// PINES DE LA CAMARA (ESP32-S3-CAM-OV3660 genérico)
// Mismos pines que en tu código principal
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
// CONFIGURACIÓN WI-FI (Punto de Acceso)
// ==========================================
const char* ssid = "Splatt_Focus";
httpd_handle_t stream_httpd = NULL;

// ==========================================
// SERVIDOR DE VÍDEO (MJPEG STREAM)
// ==========================================
#define PART_BOUNDARY "123456789000000000000987654321"
static const char* _STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char* _STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char* _STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static esp_err_t stream_handler(httpd_req_t *req) {
    camera_fb_t * fb = NULL;
    esp_err_t res = ESP_OK;
    size_t _jpg_buf_len = 0;
    uint8_t * _jpg_buf = NULL;
    char * part_buf[64];

    res = httpd_resp_set_type(req, _STREAM_CONTENT_TYPE);
    if (res != ESP_OK) return res;

    while (true) {
        fb = esp_camera_fb_get();
        if (!fb) {
            Serial.println("Fallo al capturar frame de la camara");
            res = ESP_FAIL;
        } else {
            if (fb->format != PIXFORMAT_JPEG) {
                bool jpeg_converted = frame2jpg(fb, 80, &_jpg_buf, &_jpg_buf_len);
                esp_camera_fb_return(fb);
                fb = NULL;
                if (!jpeg_converted) {
                    Serial.println("Compresión JPEG falló");
                    res = ESP_FAIL;
                }
            } else {
                _jpg_buf_len = fb->len;
                _jpg_buf = fb->buf;
            }
        }
        if (res == ESP_OK) {
            size_t hlen = snprintf((char *)part_buf, 64, _STREAM_PART, _jpg_buf_len);
            res = httpd_resp_send_chunk(req, (const char *)part_buf, hlen);
        }
        if (res == ESP_OK) {
            res = httpd_resp_send_chunk(req, (const char *)_jpg_buf, _jpg_buf_len);
        }
        if (res == ESP_OK) {
            res = httpd_resp_send_chunk(req, _STREAM_BOUNDARY, strlen(_STREAM_BOUNDARY));
        }
        if (fb) {
            esp_camera_fb_return(fb);
            fb = NULL;
            _jpg_buf = NULL;
        } else if (_jpg_buf) {
            free(_jpg_buf);
            _jpg_buf = NULL;
        }
        if (res != ESP_OK) break;
    }
    return res;
}

static esp_err_t index_handler(httpd_req_t *req){
    // Interfaz web sencilla que embebe el vídeo
    const char* html = "<html>"
                       "<head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>"
                       "<body style='text-align:center; background-color:#1e1e1e; color:white; font-family:sans-serif; margin:0; padding:10px;'>"
                       "<h2>Herramienta de Enfoque Splatt</h2>"
                       "<p>Gira la lente hasta que la imagen se vea nítida.</p>"
                       "<img src='/stream' style='max-width: 100%; border: 2px solid white;' />"
                       "</body>"
                       "</html>";
    return httpd_resp_send(req, html, strlen(html));
}

void startCameraServer() {
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = 80;

    httpd_uri_t index_uri = {
        .uri       = "/",
        .method    = HTTP_GET,
        .handler   = index_handler,
        .user_ctx  = NULL
    };

    httpd_uri_t stream_uri = {
        .uri       = "/stream",
        .method    = HTTP_GET,
        .handler   = stream_handler,
        .user_ctx  = NULL
    };

    if (httpd_start(&stream_httpd, &config) == ESP_OK) {
        httpd_register_uri_handler(stream_httpd, &index_uri);
        httpd_register_uri_handler(stream_httpd, &stream_uri);
    }
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n--- Arrancando Herramienta de Enfoque Splatt ---");

    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
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
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = 10000000;
    
    // Configuramos como JPEG para que el streaming al navegador sea fluido
    config.pixel_format = PIXFORMAT_JPEG; 
    
    // Aumentamos resolución a VGA (640x480) para facilitar el enfoque visual
    config.frame_size = FRAMESIZE_VGA; 
    config.jpeg_quality = 12; // Buena calidad para ver detalles
    config.fb_count = 1;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        Serial.printf("Error fatal al inicializar la cámara: 0x%x", err);
        return;
    }
    
    // Aplicamos los mismos ajustes oscuros que el código principal
    // Para que veas exactamente las mismas sombras y luz
    sensor_t * s = esp_camera_sensor_get();
    if(s) {
       s->set_exposure_ctrl(s, 0); 
       s->set_aec2(s, 0);
       s->set_aec_value(s, 300); // Exposición idéntica a esp32_splatt.ino
       s->set_gain_ctrl(s, 0); 
       s->set_agc_gain(s, 0);    // Ganancia idéntica
       s->set_special_effect(s, 2); // 2 = Efecto Escala de Grises (Blanco y negro real)
    }

    // Configurar Wi-Fi en modo Punto de Acceso (AP)
    WiFi.softAP(ssid);
    IPAddress IP = WiFi.softAPIP();
    
    startCameraServer();
    
    Serial.println("==================================================");
    Serial.println("¡CÁMARA LISTA PARA ENFOCAR!");
    Serial.println("1. Busca en tu móvil o PC la red Wi-Fi: Splatt_Focus");
    Serial.println("2. Conéctate a ella (no tiene contraseña).");
    Serial.print("3. Abre tu navegador web y entra en: http://");
    Serial.println(IP);
    Serial.println("==================================================");
}

void loop() {
    // El servidor HTTP funciona en segundo plano, no necesitamos hacer nada aquí.
    delay(10000);
}
