package com.netodia.tv;

/** Logarithmic bins for Android's interleaved signed 8-bit real/imaginary FFT. */
final class SpectrumData {
  static float band(byte[] fft, int index, int count) {
    int bins = fft.length / 2;
    int start = (int) Math.pow(bins - 1, index / (double) count);
    int end = Math.max(start + 1, (int) Math.pow(bins - 1, (index + 1) / (double) count));
    float peak = 0;
    for (int bin = start; bin < Math.min(bins, end); bin++)
      peak = Math.max(peak, (float) Math.hypot(fft[bin * 2], fft[bin * 2 + 1]));
    return (float) Math.min(1, Math.log1p(peak) / 5.2);
  }

  static float sample(byte sample) { return ((sample & 255) - 128) / 128f; }
}
