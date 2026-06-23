## Bug Fix: Morning Readiness "Need 2 NN Intervals" Error (Device-Aware)

**Date:** 2026-06-22

**Issue:** Morning Readiness with Polar Verity Sense (optical sensor) failed with error "need 2 nn intervals" when attempting to calculate readiness scores.

**Root Cause Analysis:**
1. The [`MorningReadinessOrchestrator.compute()`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessOrchestrator.kt:43) method had validation for the standing buffer (`MIN_STANDING_INTERVALS = 10`) but no equivalent check for the supine buffer.
2. Optical sensors (Polar Verity Sense) provide PPI (pulse-to-pulse intervals) which are noisier than true RR intervals from electrical chest straps (H10, Garmin HRM-600).
3. The system was treating all devices equally with a strict 10-interval minimum, which caused failures for optical sensors that may have data gaps or lower sample rates.

**Solution Implemented:**
1. **Device-aware validation:** Added `deviceType` parameter to [`MorningReadinessOrchestrator.Input`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessOrchestrator.kt:35)
2. **Different minimum thresholds:**
   - Electrical devices (H10, Garmin HRM-600): `MIN_SUPINE_INTERVALS_ELECTRICAL = 10` (unchanged behavior)
   - Optical sensors (Polar Verity Sense, Oximeters): `MIN_SUPINE_INTERVALS_OPTICAL = 2` (relaxed threshold)
3. **Device type detection:** Updated [`MorningReadinessViewModel`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessViewModel.kt:159) to capture and pass `sessionDeviceType` from [`UnifiedDeviceManager.connectedDeviceType()`](app/src/main/java/com/example/wags/data/ble/UnifiedDeviceManager.kt:194)
4. **Contextual error messages:** Error messages now indicate whether the user has an optical sensor and provide appropriate guidance.

**Impact:**
- **H10/Garmin HRM-600:** No change in behavior - maintains strict 10-interval requirement for accurate HRV metrics
- **Optical sensors (Polar Verity Sense):** Now works with as few as 2 intervals, with clear messaging about data quality limitations
- **User experience:** Users receive helpful, device-specific error messages rather than cryptic technical exceptions