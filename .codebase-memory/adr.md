## ADR: Bridge Generic BLE HR into Shared RR Buffer

**Date**: 2026-04-24
**Status**: Accepted

### Context
The O2 ring (and other generic BLE devices) provided HR data through `GenericBleManager.emitReadings()` → `_liveHr` StateFlow, which is why HR showed on the top bar. However, all HRV/RR interval data flowed exclusively through `PolarBleManager.rrBuffer`, which the O2 ring never wrote to. This caused:
- Meditation/NSDR sessions: flat HR/HRV charts, null RMSSD, no analytics
- Readiness scoring: no RR intervals for HRV calculation
- Breathing/resonance: no live RMSSD/SDNN updates
- Session screen: same flat-line issue

### Decision
Bridge `GenericBleManager` HR data into the shared `PolarBleManager.rrBuffer` by:
1. Adding `sharedRrBuffer` reference in `GenericBleManager`, wired by `UnifiedDeviceManager.init`
2. Writing synthesized RR intervals (60000/HR ms) in `emitReadings()` for O2Ring/OxySmart
3. Extracting real RR intervals from standard BLE HR packets (UUID 0x2A37, bit 4) in `handleStandardHrPacket()`
4. Guarding `startRrStream()` to only call Polar SDK for Polar devices
5. Adding `DeviceCapability.RR` to `OXIMETER` and `GENERIC_BLE` device types

### Consequences
- All HRV consumers now work with any device type, not just Polar
- Synthesized RR intervals from HR are less precise than real RR (no beat-to-beat variability), so RMSSD will be near-zero for O2 ring. This is expected — the O2 ring doesn't provide true RR intervals.
- Apnea code is unaffected: it uses `oximeterIsPrimary` to skip `rrBuffer` and uses `oximeterSamples` instead
- Standard BLE HR straps that include RR intervals in their packets (bit 4 of flags) will get real RR data, not synthesized