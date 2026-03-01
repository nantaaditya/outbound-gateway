package com.nantaaditya.example.helper;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.StringTokenizer;

public class StringHelper {

  private StringHelper() {}

  public static Collection<String> toCollection(String fields, String delimiter,
      Class<? extends Collection> collectionClass) {
    try {
      Collection<String> collections = (Collection<String>) collectionClass.getDeclaredConstructor().newInstance(); // NOSONAR

      if (fields == null || fields.isEmpty()) return collections;

      StringTokenizer tokenizer = delimiter == null || delimiter.isEmpty() ?
          new StringTokenizer(fields) : new StringTokenizer(fields, delimiter);

      while (tokenizer.hasMoreTokens()) {
        collections.add(tokenizer.nextToken().trim());
      }
      return collections;
    } catch (InstantiationException | IllegalAccessException
             | NoSuchMethodException | InvocationTargetException e) {
      throw new IllegalArgumentException("#Converter - error creating collection instance", e);
    }
  }

  public static String logPrepend(String value, int maxLength, char character) {
    if (value == null) {
      return value;
    }

    if (value.length() > maxLength) {
      return value.substring(value.length() - maxLength);
    }

    return String.valueOf(character).repeat(maxLength - value.length()) + value;
  }
}
