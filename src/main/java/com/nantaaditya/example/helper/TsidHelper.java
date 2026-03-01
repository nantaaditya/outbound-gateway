package com.nantaaditya.example.helper;

import com.github.f4b6a3.tsid.TsidCreator;

public class TsidHelper {

  private TsidHelper() {}

  public static String generateStringId() {
    return TsidCreator.getTsid256().toLowerCase();
  }

  public static long generateLongId() {
    return TsidCreator.getTsid256().toLong();
  }
}
