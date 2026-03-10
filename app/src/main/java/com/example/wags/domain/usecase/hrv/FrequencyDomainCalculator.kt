package com.example.wags.domain.usecase.hrv

import com.example.wags.domain.model.FrequencyDomainMetrics
import javax.inject.Inject

/**
 * Orchestrates the frequency domain HRV pipeline:
 * 1. PchipResampler — resample NN intervals to uniform 4 Hz grid
 * 2. FftProcessor   — linear detrend, Hanning window, zero-pad, FFT, PSD
 * 3. PsdBandIntegrator — trapezoidal integration over LF/HF bands
 *
 * Returns FrequencyDomainMetrics. VLF is always 0.0 (requires >5 min window).
 */
class FrequencyDomainCalculator @Inject constructor(
    private val resampler: PchipResampler,
    private val fftProcessor: FftProcessor,
    private val bandIntegrator: PsdBandIntegrator
) {

    fun calculate(correctedNn: DoubleArray): FrequencyDomainMetrics {
        if (correctedNn.size < 16) {
            return FrequencyDomainMetrics(
                vlfPowerMs2 = 0.0,
                lfPowerMs2 = 0.0,
                hfPowerMs2 = 0.0,
                lfHfRatio = 0.0,
                lfNormalizedUnits = 0.0,
                hfNormalizedUnits = 0.0
            )
        }

        // Step 1: Resample to uniform 4 Hz grid using PCHIP
        val resampled = resampler.resample(correctedNn)

        if (resampled.size < 8) {
            return FrequencyDomainMetrics(
                vlfPowerMs2 = 0.0,
                lfPowerMs2 = 0.0,
                hfPowerMs2 = 0.0,
                lfHfRatio = 0.0,
                lfNormalizedUnits = 0.0,
                hfNormalizedUnits = 0.0
            )
        }

        // Step 2: FFT with linear detrend + Hanning window
        val fftResult = fftProcessor.process(resampled)

        // Step 3: Integrate PSD over frequency bands
        return bandIntegrator.integrate(fftResult.psd, fftResult.freqAxis)
    }
}
