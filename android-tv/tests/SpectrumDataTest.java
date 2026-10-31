package com.netodia.tv;

public final class SpectrumDataTest {
  public static void main(String[] args) {
    byte[] fft = new byte[512];
    for (int i = 0; i < 48; i++) check(SpectrumData.band(fft, i, 48) == 0, "silence");
    fft[32] = 90; fft[33] = -60;
    int active = 0;
    for (int i = 0; i < 48; i++) {
      float value = SpectrumData.band(fft, i, 48);
      check(Float.isFinite(value) && value >= 0 && value <= 1, "bounded magnitude");
      if (value > 0) active++;
    }
    check(active > 0 && active < 5, "single frequency stays localized");
    java.util.Arrays.fill(fft, (byte) -128);
    for (int i = 0; i < 48; i++) check(SpectrumData.band(fft, i, 48) <= 1, "full-scale FFT");
    check(SpectrumData.sample((byte) 128) == 0, "waveform silence");
    check(SpectrumData.sample((byte) 0) == -1, "negative peak");
    check(SpectrumData.sample((byte) 255) > .99f, "positive peak");
    System.out.println("SpectrumDataTest passed");
  }
  static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
