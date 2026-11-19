// Odczyty płytki Maszyna bez Arduino: przepływomierz (czas między impulsami), ciśnienie, napięcie, GPS (NMEA).
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace ss {

// Przepływomierz: przerwanie zapisuje czas impulsu (µs). Przepływ liczy Navigator z czasu między impulsami.
class FlowMeter {
 public:
  static const uint32_t MIN_PERIOD_US = 150;       // szybsze = zakłócenie (>6 kHz)
  static const uint32_t TIMEOUT_US = 2000000;      // brak impulsów 2 s → 0
  void pulse(uint32_t nowUs);
  uint32_t count() const { return count_; }
  uint32_t periodUs(uint32_t nowUs) const;

 private:
  uint32_t count_ = 0;
  uint32_t last_ = 0;
  uint32_t period_ = 0;
  bool have_ = false;
};

enum class PressureInput : uint8_t { VOLT_05_45, MA_4_20 };

// Napięcie na wejściu AIN0 ADS1115 (za dzielnikiem 10k/20k) → ciśnienie ×0,01 bar, −1 = brak czujnika.
// 0,5–4,5 V: zasilanie 5 V; 4–20 mA: zworka JP 4‑20mA (bocznik 150 Ω przed dzielnikiem).
int32_t pressureCentiBar(float ain0Volts, PressureInput in, float rangeBar);
// AIN1 (dzielnik 100k/10k) → napięcie zasilania ×0,1 V.
uint8_t supplyDeciVolts(float ain1Volts);
bool supplyFault(uint8_t deciVolts);  // < 10,5 V albo > 16 V

// GPS płytki (Quectel L76K): z RMC prędkość, z GGA satelity i fix. Tylko do prędkości.
class NmeaSpeed {
 public:
  void feed(char c);
  int32_t speedCentiKmh() const { return valid_ ? speed_ : -1; }
  uint8_t sats() const { return sats_; }
  uint8_t fix() const { return fix_; }  // 0 brak, 1 GPS, 2 DGPS/SBAS
  // Wywoływać co 100 ms; po 2 s bez poprawnego RMC prędkość = brak.
  void age(uint32_t nowMs);

 private:
  void sentence();
  char buf_[96];
  size_t n_ = 0;
  bool valid_ = false;
  int32_t speed_ = 0;
  uint8_t sats_ = 0;
  uint8_t fix_ = 0;
  uint32_t lastRmcMs_ = 0;
  uint32_t nowMs_ = 0;
};

}  // namespace ss
