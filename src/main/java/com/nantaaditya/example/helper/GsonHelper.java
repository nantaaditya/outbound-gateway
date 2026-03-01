package com.nantaaditya.example.helper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class GsonHelper {

  private GsonHelper() {}

  public static String cleanJson(String json, Gson gson) {
    String result = "";

    if (json.startsWith("[")) {
      JsonArray array = gson.fromJson(json, JsonArray.class);
      result = gson.toJson(array);
    } else if (json.startsWith("{")) {
      JsonObject jsonObject = gson.fromJson(json, JsonObject.class);
      result = gson.toJson(jsonObject);
    }
    return result
        .replace("\\s","")
        .replace("\\t","")
        .replace("\\r","")
        .replace("\\n","")
        .replace("\\","");
  }
}
