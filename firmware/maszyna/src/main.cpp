// Firmware płytki Maszyna SmartSteer (ESP32-WROOM-32E) — etap 18a.
// Polecenia PGN 160 z Navigatora przez USB-C (CH340C) albo Bluetooth SPP, albo CAN 0x300/0x301 z płytki Ster;
// stan PGN 161 co 100 ms tą samą drogą (CAN: 0x380/0x381/0x383). Protokół: docs/MASZYNA.md.
// Logika (zawory, zabezpieczenie, odczyty) w machine.cpp / sensors.cpp — testowana na komputerze (test/).
// Licencja GPLv3.
#include <Arduino.h>
#include <BluetoothSerial.h>
#include <SPI.h>
#include <Wire.h>
#include <driver/twai.h>
#include <esp_task_wdt.h>

#include "config.h"
#include "machine.h"
#include "protocol.h"
#include "sensors.h"

using namespace ss;

static BluetoothSerial bt;
static Machine machine;
static FlowMeter flow;
static NmeaSpeed gps;
static FrameParser usbParser, btParser;
static portMUX_TYPE flowMux = portMUX_INITIALIZER_UNLOCKED;

static bool canEnabled = false;
static uint32_t lastCanRxMs = 0;
static bool canSeen = false;
static Command canCmd;

static uint8_t nFault = 0xFF;
static int32_t pressureCb = -1;
static uint8_t supplyDv = 0;
static uint32_t lastStatusMs = 0, lastTickMs = 0, lastAdcMs = 0;
static uint8_t adcChannel = 0;
static bool adcPending = false;
static bool adcOk = false;

static void IRAM_ATTR onFlowPulse() {
  portENTER_CRITICAL_ISR(&flowMux);
  flow.pulse(micros());
  portEXIT_CRITICAL_ISR(&flowMux);
}

// Jedna transakcja SPI: ~PL wczytuje nFAULT do 74HC165, potem 6 bajtów do 74HC595 (pierwszy bajt
// trafia do ostatniego rejestru), z MISO pierwszy bajt = nFAULT układów 7…0. RCLK zatrzaskuje wyjścia.
static void writeOutputs(uint64_t bits) {
  digitalWrite(PIN_PL, LOW);
  delayMicroseconds(1);
  digitalWrite(PIN_PL, HIGH);
  SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0));
  for (int r = 5; r >= 0; r--) {
    uint8_t in = SPI.transfer((uint8_t)(bits >> (8 * r)));
    if (r == 5) nFault = in;
  }
  SPI.endTransaction();
  digitalWrite(PIN_RCLK, HIGH);
  delayMicroseconds(1);
  digitalWrite(PIN_RCLK, LOW);
}

// ADS1115: pomiar pojedynczy, ±4,096 V, 128 próbek/s; kanały AIN0 (ciśnienie) i AIN1 (zasilanie) na zmianę.
static bool adsWrite(uint8_t reg, uint16_t v) {
  Wire.beginTransmission(ADS1115_ADDR);
  Wire.write(reg);
  Wire.write(v >> 8);
  Wire.write(v & 0xFF);
  return Wire.endTransmission() == 0;
}

static bool adsRead(int16_t& v) {
  Wire.beginTransmission(ADS1115_ADDR);
  Wire.write(0x00);
  if (Wire.endTransmission() != 0) return false;
  if (Wire.requestFrom(ADS1115_ADDR, 2) != 2) return false;
  v = (int16_t)((Wire.read() << 8) | Wire.read());
  return true;
}

static void adcStep(uint32_t now) {
  if (now - lastAdcMs < 50) return;
  lastAdcMs = now;
  if (adcPending) {
    int16_t raw;
    adcOk = adsRead(raw);
    if (adcOk) {
      float volts = raw * 4.096f / 32768.0f;
      if (adcChannel == 0) pressureCb = pressureCentiBar(volts, PRESSURE_INPUT, PRESSURE_RANGE_BAR);
      else supplyDv = supplyDeciVolts(volts);
    } else {
      pressureCb = -1;
    }
    adcChannel ^= 1;
  }
  uint16_t cfg = 0x8000 | ((4 + adcChannel) << 12) | (1 << 9) | (1 << 8) | (4 << 5) | 3;
  adcPending = adsWrite(0x01, cfg);
}

static void canBegin() {
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT((gpio_num_t)PIN_CAN_TX, (gpio_num_t)PIN_CAN_RX, TWAI_MODE_NORMAL);
  g.rx_queue_len = 16;
  g.tx_queue_len = 8;
  twai_timing_config_t t = TWAI_TIMING_CONFIG_250KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  canEnabled = twai_driver_install(&g, &t, &f) == ESP_OK && twai_start() == ESP_OK;
}

static void canSend(uint32_t id, const uint8_t* d, uint8_t len) {
  twai_message_t m = {};
  m.identifier = id;
  m.data_length_code = len;
  memcpy(m.data, d, len);
  twai_transmit(&m, 0);
}

static void canPoll(uint32_t now) {
  if (!canEnabled) return;
  twai_status_info_t st = {};
  if (twai_get_status_info(&st) == ESP_OK && st.state == TWAI_STATE_BUS_OFF) twai_initiate_recovery();
  if (st.state == TWAI_STATE_STOPPED) twai_start();
  twai_message_t m;
  while (twai_receive(&m, 0) == ESP_OK) {
    if (m.extd || m.rtr) continue;
    if (m.identifier == CAN_CMD && decodeCanCmd(m.data, m.data_length_code, canCmd)) {
      machine.command(canCmd, now);
      lastCanRxMs = now;
      canSeen = true;
    } else if (m.identifier == CAN_VALVE) {
      uint8_t counter;
      int16_t ms;
      if (decodeCanValve(m.data, m.data_length_code, counter, ms)) machine.regPulse(counter, ms, now);
    }
  }
}

static void handleFrame(FrameParser& p, uint32_t now) {
  if (p.pgn() != PGN_COMMAND) return;
  Command c;
  if (!decodeCommand(p.data(), p.length(), c)) return;
  machine.command(c, now);
  machine.regPulse(c.counter, c.regPulseMs, now);
}

static void sendStatus(uint32_t now) {
  Status s;
  s.counter = machine.last().counter;
  s.linkOk = true;
  s.mainOpen = machine.mainOpenNow();
  s.lights = digitalRead(PIN_LAMP) == HIGH;
  s.failsafe = machine.failsafe();
  portENTER_CRITICAL(&flowMux);
  s.pulseCount = flow.count();
  s.pulsePeriodUs = flow.periodUs(micros());
  portEXIT_CRITICAL(&flowMux);
  s.pressureCentiBar = pressureCb;
  uint8_t mf;
  machine.faults(nFault, s.sectionFaults, mf);
  s.supplyDeciVolts = supplyDv;
  if (adcOk && supplyFault(supplyDv)) mf |= FAULT_SUPPLY;
  s.faults = mf;
  gps.age(now);
  s.speedCentiKmh = gps.speedCentiKmh();
  s.sats = gps.sats();
  s.fix = gps.fix();

  uint8_t f[26];
  size_t n = buildStatusFrame(s, f);
  Serial.write(f, n);
  if (bt.hasClient()) bt.write(f, n);
  if (canEnabled && canSeen && now - lastCanRxMs < 1000) {
    uint8_t d[8];
    encodeCanFlow(s, d);
    canSend(CAN_FLOW, d, 8);
    encodeCanState(s, d);
    canSend(CAN_STATE, d, 8);
    encodeCanGps(s, d);
    canSend(CAN_GPS, d, 4);
  }

  // Diody: zielona = polecenia docierają (miga przy zabezpieczeniu), czerwona = błąd wyjścia / zasilania.
  bool blink = (now / 250) & 1;
  digitalWrite(PIN_LED_G, machine.failsafe() ? blink : HIGH);
  digitalWrite(PIN_LED_R, (s.sectionFaults || s.faults) ? HIGH : LOW);
}

void setup() {
  // Najpierw stan bezpieczny: mostki uśpione, lampy i silnik wyłączone.
  pinMode(PIN_NSLEEP, OUTPUT);
  digitalWrite(PIN_NSLEEP, LOW);
  pinMode(PIN_LAMP, OUTPUT);
  digitalWrite(PIN_LAMP, LOW);
  pinMode(PIN_MOT_PWM, OUTPUT);
  digitalWrite(PIN_MOT_PWM, LOW);
  pinMode(PIN_MOT_DIR, OUTPUT);
  digitalWrite(PIN_MOT_DIR, LOW);
  pinMode(PIN_RCLK, OUTPUT);
  digitalWrite(PIN_RCLK, LOW);
  pinMode(PIN_PL, OUTPUT);
  digitalWrite(PIN_PL, HIGH);
  pinMode(PIN_LED_G, OUTPUT);
  pinMode(PIN_LED_R, OUTPUT);
  pinMode(PIN_JP_CAN, INPUT);
  pinMode(PIN_FLOW, INPUT);

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI);
  writeOutputs(0);
  digitalWrite(PIN_NSLEEP, HIGH);  // rejestry wyzerowane — można obudzić mostki

  Serial.begin(115200);
  Serial2.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
  Wire.begin(PIN_SDA, PIN_SCL, 400000);
  bt.begin(SS_BT_NAME);
  if (digitalRead(PIN_JP_CAN) == HIGH) canBegin();
  attachInterrupt(digitalPinToInterrupt(PIN_FLOW), onFlowPulse, RISING);

  esp_task_wdt_init(2, true);  // zawieszenie > 2 s → reset (po resecie mostki uśpione)
  esp_task_wdt_add(NULL);
}

void loop() {
  uint32_t now = millis();
  while (Serial.available())
    if (usbParser.feed((uint8_t)Serial.read())) handleFrame(usbParser, now);
  while (bt.available())
    if (btParser.feed((uint8_t)bt.read())) handleFrame(btParser, now);
  while (Serial2.available()) gps.feed((char)Serial2.read());
  canPoll(now);

  if (now - lastTickMs >= 10) {
    lastTickMs = now;
    writeOutputs(machine.tick(now));
    digitalWrite(PIN_LAMP, machine.lights() ? HIGH : LOW);
  }
  adcStep(now);
  if (now - lastStatusMs >= 100) {
    lastStatusMs = now;
    sendStatus(now);
  }
  esp_task_wdt_reset();
  delay(1);
}
