#include "machine.h"

namespace ss {

int bridgeOfSection(int sectionIndex) {
  for (int b = 0; b < 13; b++)
    if (BRIDGE_SECTION[b] == sectionIndex + 1) return b;
  return -1;
}

void Machine::command(const Command& c, uint32_t nowMs) {
  cmd_ = c;
  have_ = true;
  lastCmdMs_ = nowMs;
  failsafe_ = false;
}

void Machine::regPulse(uint8_t counter, int16_t ms, uint32_t nowMs) {
  (void)nowMs;
  if (counter == lastPulseCounter_) return;
  lastPulseCounter_ = counter;
  if (failsafe_) return;
  int32_t p = regPending_ + ms;
  if (p > REG_MAX_PENDING_MS) p = REG_MAX_PENDING_MS;
  if (p < -REG_MAX_PENDING_MS) p = -REG_MAX_PENDING_MS;
  regPending_ = p;
}

void Machine::startBrake(uint32_t nowMs, uint32_t ms) {
  regDir_ = 0;
  braking_ = true;
  regBrakeUntil_ = nowMs + ms;
}

void Machine::bridge(uint64_t& bits, int b, bool ina, bool inb, bool en) const {
  if (ina) bits |= 1ull << (3 * b);
  if (inb) bits |= 1ull << (3 * b + 1);
  if (en) bits |= 1ull << (3 * b + 2);
}

uint64_t Machine::tick(uint32_t nowMs) {
  uint32_t dt = ticked_ ? nowMs - lastTickMs_ : 0;
  ticked_ = true;
  lastTickMs_ = nowMs;

  if (!have_ || nowMs - lastCmdMs_ > FAILSAFE_MS) {
    failsafe_ = true;
    regPending_ = 0;  // zawór regulacyjny zostaje w miejscu
  }
  bool active = have_ && !failsafe_;
  bool threePin = cmd_.threePin;
  uint32_t driveMs = cmd_.twoPinDriveMs;

  // Zadany stan zaworów: zabezpieczenie / brak poleceń = wszystko zamknięte.
  int8_t want[14];
  for (int b = 0; b < 13; b++) {
    int s = BRIDGE_SECTION[b] - 1;
    want[b] = (active && cmd_.mainOpen && s < cmd_.sectionCount && (cmd_.sections & (1u << s))) ? 1 : 0;
  }
  want[BRIDGE_MAIN] = (active && cmd_.mainOpen) ? 1 : 0;

  // Kolejno, co SWITCH_GAP_MS: najpierw zawór główny, potem sekcje.
  if ((int32_t)(nowMs - nextSwitchMs_) >= 0) {
    for (int i = 0; i < 14; i++) {
      int b = i == 0 ? BRIDGE_MAIN : bridgeOfSection(i - 1);  // najpierw główny, potem sekcje 1–13
      if (applied_[b] != want[b]) {
        applied_[b] = want[b];
        driving_[b] = true;
        driveUntil_[b] = nowMs + driveMs;
        nextSwitchMs_ = nowMs + SWITCH_GAP_MS;
        break;
      }
    }
  }

  uint64_t bits = 0;
  for (int b = 0; b < 14; b++) {
    if (applied_[b] < 0) continue;  // jeszcze nie przestawiony po starcie — wyłączony
    bool open = applied_[b] == 1;
    if (threePin) {
      // 3‑pin: wyjście A = sygnał (+12 otwarty, masa zamknięty), na stałe
      bridge(bits, b, open, false, true);
    } else {
      if (driving_[b] && (int32_t)(nowMs - driveUntil_[b]) >= 0) driving_[b] = false;
      // 2‑pin: biegunowość przez ustawiony czas, potem oba wyjścia wyłączone
      if (driving_[b]) bridge(bits, b, open, !open, true);
    }
  }

  // Zawór regulacyjny: zaległy impuls, potem krótkie hamowanie i wyłączenie.
  if (braking_ && (int32_t)(nowMs - regBrakeUntil_) >= 0) braking_ = false;
  if (regPending_ != 0) {
    int8_t dir = regPending_ > 0 ? 1 : -1;
    if (regDir_ != 0 && dir != regDir_) {
      startBrake(nowMs, 10);  // zmiana kierunku: chwila hamowania
    } else if (!braking_) {
      if (regDir_ == dir) {   // czas od poprzedniego taktu = czas pracy silnika
        int32_t run = (int32_t)dt;
        if (dir > 0) regPending_ = regPending_ > run ? regPending_ - run : 0;
        else regPending_ = -regPending_ > run ? regPending_ + run : 0;
      }
      regDir_ = dir;
      if (regPending_ == 0) startBrake(nowMs, REG_BRAKE_MS);
    }
  } else if (regDir_ != 0) {
    startBrake(nowMs, REG_BRAKE_MS);
  }
  if (regDir_ > 0) bridge(bits, BRIDGE_REG, true, false, true);
  else if (regDir_ < 0) bridge(bits, BRIDGE_REG, false, true, true);
  else if (braking_) bridge(bits, BRIDGE_REG, false, false, true);

  return bits;
}

void Machine::faults(uint8_t nFault, uint16_t& sectionFaults, uint8_t& machineFaults) const {
  sectionFaults = 0;
  machineFaults = 0;
  for (int k = 0; k < 8; k++) {
    if (nFault & (1u << k)) continue;  // 1 = w porządku
    for (int b = 2 * k; b <= 2 * k + 1; b++) {
      if (b < 13) {
        int s = BRIDGE_SECTION[b] - 1;
        if (s < cmd_.sectionCount) sectionFaults |= 1u << s;
      } else if (b == BRIDGE_MAIN) {
        machineFaults |= FAULT_MAIN;
      } else if (b == BRIDGE_REG) {
        machineFaults |= FAULT_REG;
      }
    }
  }
}

}  // namespace ss
