// Logika płytki Maszyna bez Arduino: polecenia, zabezpieczenie 0,5 s, zawory 2/3‑pin, zawór regulacyjny,
// stan wyjść 16 mostków DRV8962 (48 bitów do łańcucha 6 × 74HC595). Testy: firmware/maszyna/test.
//
// Bity rejestrów (hardware/maszyna/kicad/maszyna.py): mostek b → bit 3b = IN_A, 3b+1 = IN_B, 3b+2 = EN.
// Mostki: 0–12 sekcje (kolejność w BRIDGE_SECTION), 13 zawór główny, 14 zawór regulacyjny, 15 rezerwa.
// DRV8962: EN=1 + IN=1 → wyjście +12 V, EN=1 + IN=0 → masa, EN=0 → wyłączone (wysoka impedancja).
#pragma once
#include <stdint.h>

#include "protocol.h"

namespace ss {

const int BRIDGES = 16;
// Mostek 0–12 → sekcja 1–13 (płytka: hardware/maszyna/kicad/maszyna.py BRIDGE_SECTION — dobrane pod ścieżki).
const uint8_t BRIDGE_SECTION[13] = {6, 11, 5, 10, 4, 9, 12, 3, 8, 2, 13, 7, 1};
// Sekcja 1–13 (indeks 0–12) → mostek.
int bridgeOfSection(int sectionIndex);
const int BRIDGE_MAIN = 13;
const int BRIDGE_REG = 14;
const uint32_t SWITCH_GAP_MS = 50;     // sekcje przestawiane kolejno (szczyt prądu)
const uint32_t REG_BRAKE_MS = 100;     // po impulsie silnik zaworu regulacyjnego hamowany
const int32_t REG_MAX_PENDING_MS = 5000;

class Machine {
 public:
  // Polecenie z USB / Bluetooth (pełne PGN 160) albo z CAN (0x300 + osobno 0x301).
  void command(const Command& c, uint32_t nowMs);
  // Impuls zaworu regulacyjnego — wykonywany raz dla danego licznika, zaległe impulsy się sumują.
  void regPulse(uint8_t counter, int16_t ms, uint32_t nowMs);
  // Wywoływać często (co ~10 ms). Zwraca stan 48 wyjść rejestrów.
  uint64_t tick(uint32_t nowMs);

  bool failsafe() const { return failsafe_; }
  bool everCommanded() const { return have_; }
  const Command& last() const { return cmd_; }
  bool mainOpenNow() const { return applied_[BRIDGE_MAIN] == 1; }
  bool lights() const { return have_ && cmd_.lights; }
  int32_t regPendingMs() const { return regPending_; }

  // nFAULT z 74HC165 (bit k = układ k, 0 = błąd) → maski błędów do PGN 161.
  void faults(uint8_t nFault, uint16_t& sectionFaults, uint8_t& machineFaults) const;

 private:
  void startBrake(uint32_t nowMs, uint32_t ms);
  void bridge(uint64_t& bits, int b, bool ina, bool inb, bool en) const;

  Command cmd_;
  bool have_ = false;
  bool failsafe_ = true;
  uint32_t lastCmdMs_ = 0;
  int lastPulseCounter_ = -1;

  // zawory on/off (mostki 0–13): −1 nieznany (po starcie), 0 zamknięty, 1 otwarty
  int8_t applied_[14] = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
  uint32_t driveUntil_[14] = {0};
  bool driving_[14] = {false};
  uint32_t nextSwitchMs_ = 0;

  int32_t regPending_ = 0;   // ms, + otwiera
  int8_t regDir_ = 0;
  uint32_t regBrakeUntil_ = 0;
  bool braking_ = false;
  uint32_t lastTickMs_ = 0;
  bool ticked_ = false;
};

}  // namespace ss
