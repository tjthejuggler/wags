package com.example.wags.domain.usecase.hrv

import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.TransformType
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos

/**
 * FFT processor for HRV frequency domain analysis.
 *
 * Pipeline:
 * 1. Linear detrend (polyfit degree=1) — removes RR drift
 * 2. Hanning window
 * 3. Zero-pad to next power of 2
 * 4. FFT via Apache Commons Math FastFourierTransformer
 * 5. PSD calculation: psd[k] = (real² + imag²) / (N × fs)
 *
 * Critical: Uses 64s × 4Hz = 256 samples window (power-of-2).
 * VLF (0.0033 Hz) is NOT computable in a 64s window — minimum freq is 1/64 ≈ 0.0156 Hz.
 */
class FftProcessor @Inject constructor() {

    companion object {
        const val SAMPLE_RATE_HZ = 4.0
        const val WINDOW_SECONDS = 64
        const val WINDOW_SAMPLES = WINDOW_SECONDS * SAMPLE_RATE_HZ.toInt()  // 256
    }

    data class FftResult(
        val psd: DoubleArray,
        val freqAxis: DoubleArray,
        val sampleRate: Double
    )

    fun process(resampledNn: DoubleArray): FftResult {
        val n = minOf(resampledNn.size, WINDOW_SAMPLES)
        val windowed = resampledNn.takeLast(n).toDoubleArray()

        // Step 1: Linear detrend (polyfit degree=1)
        val detrended = linearDetrend(windowed)

        // Step 2: Hanning window
        val hannWindowed = DoubleArray(n) { i ->
            detrended[i] * 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
        }

        // Step 3: Zero-pad to next power of 2
        val fftSize = nextPowerOf2(n)
        val padded = DoubleArray(fftSize)
        hannWindowed.copyInto(padded)

        // Step 4: FFT
        val fft = FastFourierTransformer(DftNormalization.STANDARD)
        val complex = fft.transform(padded, TransformType.FORWARD)

        // Step 5: PSD — one-sided spectrum
        val halfSize = fftSize / 2
        val psd = DoubleArray(halfSize) { k ->
            val re = complex[k].real
            val im = complex[k].imaginary
            (re * re + im * im) / (fftSize * SAMPLE_RATE_HZ)
        }

        val freqAxis = DoubleArray(halfSize) { k -> k * SAMPLE_RATE_HZ / fftSize }

        return FftResult(psd, freqAxis, SAMPLE_RATE_HZ)
    }

    private fun linearDetrend(data: DoubleArray): DoubleArray {
        val n = data.size
        val meanX = (n - 1) / 2.0
        val meanY = data.average()
        var ssXX = 0.0
        var ssXY = 0.0
        for (i in data.indices) {
            val dx = i - meanX
            ssXX += dx * dx
            ssXY += dx * (data[i] - meanY)
        }
        val slope = if (ssXX != 0.0) ssXY / ssXX else 0.0
        val intercept = meanY - slope * meanX
        return DoubleArray(n) { i -> data[i] - (slope * i + intercept) }
    }

    private fun nextPowerOf2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}
