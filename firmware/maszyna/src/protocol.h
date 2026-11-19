// Protokół opryskiwacza SmartSteer — strona płytki Maszyna (opis: docs/MASZYNA.md).
// Ten sam układ bajtów co composeApp/.../core/sprayer/SprayerProtocol.kt (Navigator).
// Czysty C++ bez Arduino — testowany na komputerze (firmware/maszyna/test).
// Ramka AgOpenGPS: 0x80 0x81 źródło PGN długość dane CRC; CRC = młodszy bajt sumy od źródła do końca danych.
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace ss {

const uint8_t PGN_COMMAND = 0xA0;  // 160: Navigator -> Maszyna
const uint8_t PGN_STATUS = 0xA1;   // 161: Maszyna -> Navigator
const uint8_t SOURCE_MACHINE = 0x7E;
const int MAX_SECTIONS = 13;
const uint32_t FAILSAFE_MS = 500;

const uint32_t CAN_CMD = 0x300;
const uint32_t CAN_VALVE = 0x301;
const uint32_t CAN_FLOW = 0x380;
const uint32_t CAN_STATE = 0x381;
const uint32_t CAN_GPS = 0x383;

const uint8_t FAULT_MAIN = 1, FAULT_REG = 2, FAULT_LIGHTS = 4, FAULT_SUPPLY = 8;

struct Command {
  uint8_t counter = 0;
  bool mainOpen = false;
  bool lights = false;
  bool threePin = true;
  uint16_t sections = 0;      // bit0 = sekcja 1
  uint8_t sectionCount = 1;   // 1–13
  uint16_t twoPinDriveMs = 3000;
  int16_t regPulseMs = 0;     // + więcej cieczy, − mniej; raz na licznik
};

struct Status {
  uint8_t counter = 0;
  bool linkOk = true;
  bool mainOpen = false;
  bool lights = false;
  bool failsafe = false;
  uint32_t pulseCount = 0;
  uint32_t pulsePeriodUs = 0;    // 0 = brak impulsów od 2 s
  int32_t pressureCentiBar = -1; // −1 = brak czujnika
  uint16_t sectionFaults = 0;
  uint8_t faults = 0;
  uint8_t supplyDeciVolts = 0;
  int32_t speedCentiKmh = -1;    // −1 = brak fixu GPS płytki
  uint8_t sats = 0;
  uint8_t fix = 0;
};

uint8_t crc(const uint8_t* frame, size_t total);
// Bajty 0–7 PGN 160 → Command. false, gdy za krótkie albo liczba sekcji poza 1–13.
bool decodeCommand(const uint8_t* d, size_t n, Command& c);
// 20 bajtów danych PGN 161.
void encodeStatusData(const Status& s, uint8_t out[20]);
// Pełna ramka PGN 161 (26 bajtów). Zwraca długość.
size_t buildStatusFrame(const Status& s, uint8_t out[26]);

// CAN (Ster ↔ Maszyna): 0x300 = bajty 0–5 PGN 160, 0x301 = licznik + impuls zaworu regulacyjnego.
bool decodeCanCmd(const uint8_t* d, uint8_t len, Command& c);  // ustawia wszystko poza regPulseMs
bool decodeCanValve(const uint8_t* d, uint8_t len, uint8_t& counter, int16_t& pulseMs);
void encodeCanFlow(const Status& s, uint8_t out[8]);   // 0x380: bajty 2–9 PGN 161
void encodeCanState(const Status& s, uint8_t out[8]);  // 0x381
void encodeCanGps(const Status& s, uint8_t out[4]);    // 0x383: bajty 16–19 PGN 161

// Składanie ramek ze strumienia (USB / Bluetooth): bajt po bajcie.
class FrameParser {
 public:
  // true = kompletna ramka z poprawnym CRC jest w pgn()/data()/length().
  bool feed(uint8_t b);
  uint8_t pgn() const { return buf_[3]; }
  uint8_t source() const { return buf_[2]; }
  const uint8_t* data() const { return buf_ + 5; }
  uint8_t length() const { return buf_[4]; }
  uint32_t crcErrors() const { return crcErrors_; }

 private:
  uint8_t buf_[261];
  size_t pos_ = 0;
  uint32_t crcErrors_ = 0;
};

}  // namespace ss
