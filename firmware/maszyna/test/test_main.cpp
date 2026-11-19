// Testy logiki firmware płytki Maszyna na komputerze (bez ESP32):
//   g++ -std=c++17 -Wall -Wextra -I ../src ../src/*.cpp test_main.cpp -o t && ./t
// Wektory ramek wspólne z Navigatorem: composeApp/src/commonTest/.../sprayer/SprayerFirmwareVectorTest.kt
#include <stdio.h>
#include <string.h>

#include "machine.h"
#include "protocol.h"
#include "sensors.h"

using namespace ss;

static int fails = 0, checks = 0;
#define CHECK(c)                                                    \
  do {                                                              \
    checks++;                                                       \
    if (!(c)) {                                                     \
      fails++;                                                      \
      printf("BŁĄD %s:%d: %s\n", __FILE__, __LINE__, #c);           \
    }                                                               \
  } while (0)

static bool bit(uint64_t bits, int b, int k) { return (bits >> (3 * b + k)) & 1; }
static bool en(uint64_t bits, int b) { return bit(bits, b, 2); }
static bool ina(uint64_t bits, int b) { return bit(bits, b, 0); }
static bool inb(uint64_t bits, int b) { return bit(bits, b, 1); }

// Ramka z Navigatora: SprayerCommand(5, mainOpen, sekcje 1 i 3 z 3, 3‑pin, lampy, 2,5 s, impuls −120 ms)
static const uint8_t CMD_FRAME[] = {0x80, 0x81, 0x7F, 0xA0, 0x08, 0x05, 0x07, 0x05, 0x00, 0x03, 0x19, 0x88, 0xFF, 0xDB};

static void testProtocol() {
  FrameParser p;
  int got = 0;
  // śmieci przed ramką i ramka z błędnym CRC
  const uint8_t junk[] = {0x00, 0x80, 0x80, 0x81, 0x7F, 0xA0, 0x08, 1, 2, 3, 4, 3, 6, 7, 8, 0x00};
  for (uint8_t b : junk) got += p.feed(b);
  CHECK(got == 0);
  CHECK(p.crcErrors() == 1);
  for (uint8_t b : CMD_FRAME) got += p.feed(b);
  CHECK(got == 1);
  CHECK(p.pgn() == PGN_COMMAND);
  Command c;
  CHECK(decodeCommand(p.data(), p.length(), c));
  CHECK(c.counter == 5 && c.mainOpen && c.lights && c.threePin);
  CHECK(c.sections == 0x5 && c.sectionCount == 3);
  CHECK(c.twoPinDriveMs == 2500 && c.regPulseMs == -120);
  uint8_t bad[8] = {1, 1, 0xFF, 0xFF, 14, 0, 0, 0};
  CHECK(!decodeCommand(bad, 8, c));
  bad[4] = 2;
  CHECK(decodeCommand(bad, 8, c));
  CHECK(c.sections == 0x3);  // maska przycięta do liczby sekcji
  CHECK(c.twoPinDriveMs == 3000);

  Status s;
  s.counter = 9; s.mainOpen = true; s.pulseCount = 0x01020304; s.pulsePeriodUs = 23529;
  s.pressureCentiBar = 312; s.sectionFaults = 0x0102; s.faults = FAULT_REG; s.supplyDeciVolts = 138;
  s.speedCentiKmh = 815; s.sats = 11; s.fix = 2;
  uint8_t f[26];
  CHECK(buildStatusFrame(s, f) == 26);
  const uint8_t want[26] = {0x80, 0x81, 0x7E, 0xA1, 0x14, 0x09, 0x03, 0x04, 0x03, 0x02, 0x01, 0xE9, 0x5B, 0x00, 0x00,
                            0x38, 0x01, 0x02, 0x01, 0x02, 0x8A, 0x2F, 0x03, 0x0B, 0x02, 0x00};
  uint8_t w[26];
  memcpy(w, want, 26);
  w[25] = crc(w, 26);
  CHECK(memcmp(f, w, 26) == 0);
  printf("ramka PGN 161:");
  for (int i = 0; i < 26; i++) printf(" %02X", f[i]);
  printf("\n");
  Status none;
  uint8_t d[20];
  encodeStatusData(none, d);
  CHECK(d[10] == 0xFF && d[11] == 0xFF && d[16] == 0xFF && d[17] == 0xFF);

  // CAN: 0x300 + 0x301 dają to samo co PGN 160
  Command k;
  CHECK(decodeCanCmd(CMD_FRAME + 5, 8, k));
  CHECK(k.sections == 0x5 && k.mainOpen && k.twoPinDriveMs == 2500);
  uint8_t cv[3] = {5, 0x88, 0xFF}, cnt;
  int16_t ms;
  CHECK(decodeCanValve(cv, 3, cnt, ms) && cnt == 5 && ms == -120);
  uint8_t c8[8], g4[4];
  encodeCanFlow(s, c8);
  CHECK(c8[0] == 0x04 && c8[3] == 0x01 && c8[4] == 0xE9);
  encodeCanState(s, c8);
  CHECK(c8[0] == 9 && c8[2] == 0x38 && c8[3] == 0x01 && c8[7] == 138);
  encodeCanGps(s, g4);
  CHECK(g4[0] == 0x2F && g4[1] == 0x03 && g4[2] == 11 && g4[3] == 2);
}

static Command cmd(uint8_t counter, bool main, uint16_t sec, uint8_t n, bool threePin) {
  Command c;
  c.counter = counter; c.mainOpen = main; c.sections = sec; c.sectionCount = n; c.threePin = threePin;
  c.twoPinDriveMs = 1000;
  return c;
}

static void testFailsafeAndThreePin() {
  Machine m;
  uint64_t b = m.tick(0);
  CHECK(m.failsafe());
  // po starcie: zawory przestawiane kolejno na „zamknięte”
  for (uint32_t t = 10; t <= 1000; t += 10) b = m.tick(t);
  for (int i = 0; i < 14; i++) CHECK(en(b, i) && !ina(b, i));  // 3‑pin: sygnał 0 = zamknięty
  // START: główny + sekcje 1,2,4 z 5
  uint32_t t = 1000;
  for (; t < 1400; t += 10) {
    m.command(cmd((uint8_t)(t / 100), true, 0b01011, 5, true), t);
    b = m.tick(t);
  }
  CHECK(!m.failsafe());
  CHECK(ina(b, BRIDGE_MAIN) && ina(b, 0) && ina(b, 1) && !ina(b, 2) && ina(b, 3) && !ina(b, 4));
  CHECK(!ina(b, 5));  // poza liczbą sekcji
  // kolejne przestawianie: zaraz po poleceniu tylko jeden zawór
  Machine m2;
  m2.command(cmd(1, true, 0x1FFF, 13, true), 0);
  b = m2.tick(0);
  int on = 0;
  for (int i = 0; i < 14; i++) on += ina(b, i);
  CHECK(on == 1 && ina(b, BRIDGE_MAIN));  // najpierw zawór główny
  // brak poleceń > 0,5 s → wszystko zamknięte
  for (; t < 2400; t += 10) b = m.tick(t);
  CHECK(m.failsafe());
  for (int i = 0; i < 14; i++) CHECK(!ina(b, i));
}

static void testTwoPin() {
  Machine m;
  uint32_t t = 0;
  uint64_t b = 0;
  // 2‑pin: zamknij przez 1 s (B), potem wyłączone
  for (; t <= 2000; t += 10) {
    m.command(cmd((uint8_t)t, false, 0, 1, false), t);
    b = m.tick(t);
    if (t == 100) CHECK(en(b, BRIDGE_MAIN) && !ina(b, BRIDGE_MAIN) && inb(b, BRIDGE_MAIN));
  }
  for (int i = 0; i < 14; i++) CHECK(!en(b, i));
  // otwórz sekcję 1 i główny: A=+12 przez 1 s
  uint32_t start = t;
  bool sawOpen = false;
  for (; t <= start + 2000; t += 10) {
    m.command(cmd((uint8_t)t, true, 1, 1, false), t);
    b = m.tick(t);
    if (t == start + 500) {
      sawOpen = en(b, 0) && ina(b, 0) && !inb(b, 0) && en(b, BRIDGE_MAIN) && ina(b, BRIDGE_MAIN);
    }
  }
  CHECK(sawOpen);
  CHECK(!en(b, 0) && !en(b, BRIDGE_MAIN));
}

static void testRegValve() {
  Machine m;
  uint32_t t = 0;
  m.command(cmd(1, true, 1, 1, true), t);
  m.regPulse(1, 200, t);
  m.regPulse(1, 200, t);  // powtórzona ramka — bez drugiego impulsu
  CHECK(m.regPendingMs() == 200);
  int runMs = 0;
  uint64_t b = 0;
  for (; t < 400; t += 10) {
    m.command(cmd(1, true, 1, 1, true), t);
    b = m.tick(t);
    if (en(b, BRIDGE_REG) && ina(b, BRIDGE_REG)) runMs += 10;
  }
  CHECK(runMs >= 200 && runMs <= 220);
  CHECK(!en(b, BRIDGE_REG));
  // impuls w drugą stronę (mniej cieczy)
  m.regPulse(2, -50, t);
  bool sawClose = false;
  for (uint32_t e = t + 200; t < e; t += 10) {
    m.command(cmd(2, true, 1, 1, true), t);
    b = m.tick(t);
    sawClose |= en(b, BRIDGE_REG) && inb(b, BRIDGE_REG) && !ina(b, BRIDGE_REG);
  }
  CHECK(sawClose);
  // zabezpieczenie: impuls skasowany, zawór regulacyjny stoi
  m.regPulse(3, 1000, t);
  for (uint32_t e = t + 1000; t < e; t += 10) b = m.tick(t);
  CHECK(m.failsafe() && m.regPendingMs() == 0 && !ina(b, BRIDGE_REG) && !inb(b, BRIDGE_REG));
}

static void testFaults() {
  Machine m;
  m.command(cmd(1, true, 0x1FFF, 10, true), 0);
  uint16_t sf;
  uint8_t mf;
  m.faults(0xFF, sf, mf);
  CHECK(sf == 0 && mf == 0);
  m.faults((uint8_t)~((1 << 0) | (1 << 6) | (1 << 7)), sf, mf);  // układy 0, 6, 7
  CHECK(sf == 0x3);                 // sekcje 1–2 (12–13 poza liczbą sekcji)
  CHECK(mf == (FAULT_MAIN | FAULT_REG));
}

static void testSensors() {
  FlowMeter f;
  CHECK(f.periodUs(0) == 0);
  f.pulse(1000);
  f.pulse(1050);  // zakłócenie
  f.pulse(11000);
  f.pulse(21000);
  CHECK(f.count() == 3);
  CHECK(f.periodUs(25000) == 10000);
  CHECK(f.periodUs(40000) == 19000);  // przepływ maleje
  CHECK(f.periodUs(21000 + 2100000) == 0);

  CHECK(pressureCentiBar(0.0f, PressureInput::VOLT_05_45, 10) == -1);
  CHECK(pressureCentiBar(0.5f / 1.5f, PressureInput::VOLT_05_45, 10) == 0);
  CHECK(pressureCentiBar(2.5f / 1.5f, PressureInput::VOLT_05_45, 10) == 500);
  CHECK(pressureCentiBar(3.0f, PressureInput::VOLT_05_45, 10) == 1000);
  CHECK(pressureCentiBar(12e-3f * 150 / 1.5f, PressureInput::MA_4_20, 20) == 1000);
  CHECK(pressureCentiBar(0.1f, PressureInput::MA_4_20, 20) == -1);
  CHECK(supplyDeciVolts(13.8f / 11) == 138);
  CHECK(!supplyFault(138) && supplyFault(95) && supplyFault(170));

  NmeaSpeed n;
  // zdania przykładowe, sumy kontrolne liczone tutaj
  char buf[2][128];
  const char* lines[2] = {"GNGGA,123519,4807.038,N,01131.000,E,2,09,0.9,545.4,M,46.9,M,,",
                          "GNRMC,123519,A,4807.038,N,01131.000,E,004.4,084.4,230394,003.1,W"};
  for (int i = 0; i < 2; i++) {
    uint8_t x = 0;
    for (const char* p = lines[i]; *p; p++) x ^= (uint8_t)*p;
    snprintf(buf[i], sizeof buf[i], "$%s*%02X\r\n", lines[i], x);
    for (const char* p = buf[i]; *p; p++) n.feed(*p);
  }
  n.age(100);
  CHECK(n.speedCentiKmh() == 815);  // 4,4 węzła = 8,15 km/h
  CHECK(n.sats() == 9 && n.fix() == 2);
  for (const char* p = "$GNRMC,1,A,,,,,9.0,,,,*00\r\n"; *p; p++) n.feed(*p);  // zła suma
  CHECK(n.speedCentiKmh() == 815);
  n.age(2500);
  CHECK(n.speedCentiKmh() == -1);
}

int main() {
  testProtocol();
  testFailsafeAndThreePin();
  testTwoPin();
  testRegValve();
  testFaults();
  testSensors();
  printf("%d sprawdzeń, %d błędów\n", checks, fails);
  return fails ? 1 : 0;
}
