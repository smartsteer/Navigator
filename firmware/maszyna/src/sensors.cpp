#include "sensors.h"

#include <stdlib.h>
#include <string.h>

namespace ss {

void FlowMeter::pulse(uint32_t nowUs) {
  if (have_) {
    uint32_t p = nowUs - last_;
    if (p < MIN_PERIOD_US) return;
    period_ = p;
  }
  have_ = true;
  last_ = nowUs;
  count_++;
}

uint32_t FlowMeter::periodUs(uint32_t nowUs) const {
  if (!have_ || period_ == 0) return 0;
  uint32_t since = nowUs - last_;
  if (since > TIMEOUT_US) return 0;
  // Przepływ wyraźnie maleje (przerwa > 1,5 × ostatni okres): podajemy przerwę — szybsza reakcja na zamknięcie.
  return since > period_ + period_ / 2 ? since : period_;
}

int32_t pressureCentiBar(float ain0Volts, PressureInput in, float rangeBar) {
  float v = ain0Volts * 1.5f;  // dzielnik 10k/20k: 4,5 V → 3,0 V
  float frac;
  if (in == PressureInput::VOLT_05_45) {
    if (v < 0.2f) return -1;  // brak czujnika / przerwa
    frac = (v - 0.5f) / 4.0f;
  } else {
    float ma = v / 150.0f * 1000.0f;
    if (ma < 3.0f) return -1;
    frac = (ma - 4.0f) / 16.0f;
  }
  if (frac < 0) frac = 0;
  int32_t cb = (int32_t)(frac * rangeBar * 100.0f + 0.5f);
  return cb > 0xFFFE ? 0xFFFE : cb;
}

uint8_t supplyDeciVolts(float ain1Volts) {
  float v = ain1Volts * 11.0f * 10.0f;  // dzielnik 100k/10k
  if (v < 0) v = 0;
  if (v > 255) v = 255;
  return (uint8_t)(v + 0.5f);
}

bool supplyFault(uint8_t dv) { return dv < 105 || dv > 160; }

static int hex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

void NmeaSpeed::feed(char c) {
  if (c == '$') {
    n_ = 0;
    buf_[n_++] = c;
    return;
  }
  if (n_ == 0) return;
  if (c == '\r' || c == '\n') {
    buf_[n_] = 0;
    sentence();
    n_ = 0;
    return;
  }
  if (n_ < sizeof(buf_) - 1) buf_[n_++] = c;
  else n_ = 0;
}

void NmeaSpeed::age(uint32_t nowMs) {
  nowMs_ = nowMs;
  if (valid_ && nowMs - lastRmcMs_ > 2000) valid_ = false;
}

// Pole nr i (0 = nazwa zdania), kopiowane do out.
static bool field(const char* s, int i, char* out, size_t outLen) {
  int k = 0;
  while (*s && k < i) {
    if (*s == ',') k++;
    s++;
  }
  if (k != i) return false;
  size_t n = 0;
  while (*s && *s != ',' && *s != '*' && n < outLen - 1) out[n++] = *s++;
  out[n] = 0;
  return true;
}

void NmeaSpeed::sentence() {
  const char* star = strchr(buf_, '*');
  if (!star || star[1] == 0 || star[2] == 0) return;
  uint8_t sum = 0;
  for (const char* p = buf_ + 1; p < star; p++) sum ^= (uint8_t)*p;
  int h = hex(star[1]), l = hex(star[2]);
  if (h < 0 || l < 0 || sum != (uint8_t)(h * 16 + l)) return;
  if (strlen(buf_) < 6) return;
  const char* type = buf_ + 3;  // $GPRMC, $GNRMC, …
  char f[16];
  if (strncmp(type, "RMC", 3) == 0) {
    if (!field(buf_, 2, f, sizeof f) || f[0] != 'A') {
      valid_ = false;
      return;
    }
    if (!field(buf_, 7, f, sizeof f)) return;
    float knots = (float)atof(f);
    speed_ = (int32_t)(knots * 1.852f * 100.0f + 0.5f);
    valid_ = true;
    lastRmcMs_ = nowMs_;
  } else if (strncmp(type, "GGA", 3) == 0) {
    if (field(buf_, 6, f, sizeof f)) {
      int q = atoi(f);
      fix_ = q == 0 ? 0 : (q == 2 ? 2 : 1);
    }
    if (field(buf_, 7, f, sizeof f)) sats_ = (uint8_t)atoi(f);
  }
}

}  // namespace ss
