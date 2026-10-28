package com.netodia.tv;

final class Native {
  static {
    System.loadLibrary("netodia_android");
  }

  static native String call(String operation, String input);
}
