#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <SPIFFS.h>
#include "esp_sleep.h"
#include "mbedtls/aes.h"
#include "mbedtls/base64.h"

// ---- Configuración ----
#define DHTPIN 4
#define LEDPIN 23
#define DHTTYPE DHT22

const char* WIFI_SSID     = "Wokwi-GUEST";
const char* WIFI_PASSWORD = "";
const char* MQTT_HOST     = "test.mosquitto.org";
const uint16_t MQTT_PORT  = 1883;
const char* CLIENT_ID     = "smartnode-esp32";

const char* TOPIC_CMD       = "smartnode/cmd";
const char* TOPIC_TELEMETRY = "smartnode/telemetry";
const unsigned long READ_INTERVAL_MS = 2000;

// Clave AES-128 compartida con la app Android (16 bytes).
// DEBE ser idéntica en ambos lados (CryptoUtil.kt).
const char* AES_KEY = "SmartNode2026Key";

// ---- Buffer offline persistente en SPIFFS (tolerancia a fallos) ----
#define BUF_FILE "/telemetry_buf.txt"
const size_t BUF_SIZE = 20;

// ---- Gestión de energía: deep sleep solo cuando no hay conexión ----
const uint64_t SLEEP_US = 30ULL * 1000000ULL;   // 30 s de sueño
const unsigned long WAKE_GRACE_MS = 15000;      // tiempo para reconectar tras despertar
unsigned long bootTime = 0;

struct Telemetry {
  float temp;
  float hum;
  unsigned long ts;
};
Telemetry buf[BUF_SIZE];
size_t bufCount = 0;

DHT dht(DHTPIN, DHTTYPE);
WiFiClient net;
PubSubClient mqtt(net);

unsigned long lastRead = 0;
unsigned long lastWifiAttempt = 0;
unsigned long lastMqttAttempt = 0;

// ================= Cifrado AES-128-CBC (PKCS7) =================
// Formato publicado: Base64( IV(16) + ciphertext ) — igual que CryptoUtil.kt

String encryptPayload(const String& plaintext) {
  size_t len = plaintext.length();
  size_t padded = ((len / 16) + 1) * 16;          // siempre deja >=1 byte de padding
  if (padded > 112) return "";

  uint8_t iv[16], ivSave[16], block[112];
  memcpy(block, plaintext.c_str(), len);
  memset(block + len, (int)(padded - len), padded - len); // PKCS7
  for (int i = 0; i < 16; i++) iv[i] = (uint8_t)esp_random();
  memcpy(ivSave, iv, 16);

  mbedtls_aes_context ctx;
  mbedtls_aes_init(&ctx);
  mbedtls_aes_setkey_enc(&ctx, (const unsigned char*)AES_KEY, 128);
  mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_ENCRYPT, padded, iv, block, block);
  mbedtls_aes_free(&ctx);

  uint8_t out[128];
  memcpy(out, ivSave, 16);
  memcpy(out + 16, block, padded);

  unsigned char b64[256];
  size_t olen;
  if (mbedtls_base64_encode(b64, sizeof(b64), &olen, out, 16 + padded) != 0) return "";
  return String((char*)b64);
}

String decryptPayload(const String& b64) {
  uint8_t buf[256];
  size_t len;
  if (mbedtls_base64_decode(buf, sizeof(buf), &len,
                            (const unsigned char*)b64.c_str(), b64.length()) != 0) return "";
  if (len < 32 || ((len - 16) % 16) != 0) return "";

  uint8_t iv[16];
  memcpy(iv, buf, 16);
  mbedtls_aes_context ctx;
  mbedtls_aes_init(&ctx);
  mbedtls_aes_setkey_dec(&ctx, (const unsigned char*)AES_KEY, 128);
  mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_DECRYPT, len - 16, iv, buf + 16, buf + 16);
  mbedtls_aes_free(&ctx);

  uint8_t pad = buf[len - 1];
  if (pad == 0 || pad > 16) return "";
  return String((char*)(buf + 16)).substring(0, len - 16 - pad);
}

// ================= Publicación cifrada =================

bool publishTelemetry(const Telemetry& t) {
  char json[64];
  snprintf(json, sizeof(json), "{\"temp\":%.2f,\"hum\":%.2f,\"ts\":%lu}", t.temp, t.hum, t.ts);
  String encrypted = encryptPayload(String(json));
  if (encrypted.length() == 0) return false;
  return mqtt.publish(TOPIC_TELEMETRY, encrypted.c_str(), 1);
}

// ================= Buffer offline (RAM + SPIFFS) =================

void bufferFileRewrite() {  // el archivo es espejo del buffer RAM
  SPIFFS.remove(BUF_FILE);
  File f = SPIFFS.open(BUF_FILE, FILE_WRITE);
  if (!f) return;
  for (size_t i = 0; i < bufCount; i++) {
    f.printf("%.2f,%.2f,%lu\n", buf[i].temp, buf[i].hum, buf[i].ts);
  }
  f.close();
}

void bufferFileLoad() {
  if (!SPIFFS.exists(BUF_FILE)) return;
  File f = SPIFFS.open(BUF_FILE, FILE_READ);
  if (!f) return;
  while (f.available() && bufCount < BUF_SIZE) {
    String line = f.readStringUntil('\n');
    float t, h;
    unsigned long ts;
    if (sscanf(line.c_str(), "%f,%f,%lu", &t, &h, &ts) == 3) {
      buf[bufCount++] = { t, h, ts };
    }
  }
  f.close();
}

void bufferPush(const Telemetry& t) {
  if (bufCount == BUF_SIZE) {           // descarta la más antigua
    for (size_t i = 1; i < BUF_SIZE; i++) buf[i - 1] = buf[i];
    bufCount--;
  }
  buf[bufCount++] = t;
  bufferFileRewrite();                  // persiste: sobrevive reinicios/deep sleep
}

void flushBuffer() {
  while (bufCount > 0 && mqtt.connected()) {
    if (!publishTelemetry(buf[0])) return;
    for (size_t i = 1; i < bufCount; i++) buf[i - 1] = buf[i];
    bufCount--;
    bufferFileRewrite();
    Serial.printf("Lectura pendiente sincronizada (quedan: %u)\n", bufCount);
  }
}

// ================= Conexión =================

void connectWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (millis() - lastWifiAttempt < 3000) return;
  lastWifiAttempt = millis();
  Serial.println("Conectando a WiFi...");
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void connectMQTT() {
  if (mqtt.connected() || WiFi.status() != WL_CONNECTED) return;
  if (millis() - lastMqttAttempt < 3000) return;
  lastMqttAttempt = millis();
  Serial.println("Conectando a MQTT...");
  if (mqtt.connect(CLIENT_ID)) {
    Serial.println("MQTT OK");
    mqtt.subscribe(TOPIC_CMD, 1);
  }
}

void callback(char* topic, byte* payload, unsigned int length) {
  String encrypted;
  encrypted.reserve(length);
  for (unsigned int i = 0; i < length; i++) encrypted += (char)payload[i];

  String cmd = decryptPayload(encrypted);
  if (cmd.length() == 0) {
    Serial.println("Comando con cifrado invalido, ignorado");
    return;
  }
  Serial.printf("Comando recibido (descifrado): %s\n", cmd.c_str());
  if (cmd == "led_on")  digitalWrite(LEDPIN, HIGH);
  if (cmd == "led_off") digitalWrite(LEDPIN, LOW);
}

// ================= Lectura y publicación =================

void takeReading() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) {
    Serial.println("Error de lectura DHT22");
    return;
  }
  Telemetry reading = { t, h, millis() / 1000 };

  if (mqtt.connected()) {
    if (publishTelemetry(reading)) {
      flushBuffer();
      Serial.printf("Publicado (AES): temp=%.1f hum=%.1f (pendientes: %u)\n", t, h, bufCount);
    } else {
      bufferPush(reading);
      Serial.printf("Fallo publicacion, almacenado (pendientes: %u)\n", bufCount);
    }
  } else {
    bufferPush(reading);
    Serial.printf("Offline, almacenado en SPIFFS: temp=%.1f hum=%.1f (pendientes: %u)\n", t, h, bufCount);
  }
}

void setup() {
  Serial.begin(115200);
  bootTime = millis();
  pinMode(LEDPIN, OUTPUT);
  digitalWrite(LEDPIN, LOW);

  if (!SPIFFS.begin(true)) Serial.println("SPIFFS init fallo");
  bufferFileLoad();
  Serial.printf("SmartNode ESP32 iniciado (pendientes en SPIFFS: %u)\n", bufCount);

  dht.begin();
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setCallback(callback);
}

void loop() {
  connectWiFi();
  connectMQTT();
  mqtt.loop();

  bool online = WiFi.status() == WL_CONNECTED && mqtt.connected();

  // Gracia tras despertar: dar tiempo a reconectar antes de decidir dormir
  if (!online && millis() - bootTime < WAKE_GRACE_MS) {
    delay(50);
    return;
  }

  if (!online) {
    // Plan de contingencia + gestión de energía: leer, guardar y dormir
    takeReading();
    Serial.println("Sin conexion: deep sleep 30s (despertar, medir, sincronizar)");
    esp_sleep_enable_timer_wakeup(SLEEP_US);
    esp_deep_sleep_start();   // al despertar, setup() corre de nuevo
  }

  if (millis() - lastRead < READ_INTERVAL_MS) {
    delay(20);
    return;
  }
  lastRead = millis();
  takeReading();
}
