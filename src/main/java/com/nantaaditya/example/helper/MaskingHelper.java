package com.nantaaditya.example.helper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nantaaditya.example.model.logger.AppLogMessage;
import java.util.Set;
import lombok.extern.log4j.Log4j2;
import org.springframework.util.StringUtils;

@Log4j2
public class MaskingHelper {

  public static final String MASKED_CHAR = "*";

  private MaskingHelper() {}

  public static String masking(String value) {
    if (!StringUtils.hasText(value) || value.length() <=2) return value;

    int valueLength = value.length();
    int halfCharLength = valueLength / 2;
    int startCharLength = (valueLength - halfCharLength) / 2;

    return masking(value, startCharLength, startCharLength);
  }

  public static String masking(String value, int nStartChar, int nEndChar) {
    int totalLength = nStartChar + nEndChar;
    if (!StringUtils.hasText(value) || value.length() <= totalLength) return value;

    StringBuilder sb = new StringBuilder();
    sb.append(value, 0, nStartChar);
    sb.append(MASKED_CHAR.repeat(value.length() - totalLength));
    sb.append(value, sb.length(), value.length());
    return sb.toString();
  }

  public static String maskingJson(Gson gson, Set<String> maskingKeys, String jsonPayload) {
    if (!StringUtils.hasText(jsonPayload))
      return jsonPayload;

    String content = null;
    try {
      if (jsonPayload.startsWith("[")) {
        JsonArray jsonArray = gson.fromJson(jsonPayload, JsonArray.class);
        for (String key : maskingKeys) {
          maskArray(jsonArray, key);
        }
        content = GsonHelper.cleanJson(gson.toJson(jsonArray), gson);
      } else if (jsonPayload.startsWith("{")) {
        JsonObject jsonObject = gson.fromJson(jsonPayload, JsonObject.class);
        for (String key : maskingKeys) {
          maskObject(jsonObject, key);
        }
        content = GsonHelper.cleanJson(gson.toJson(jsonObject), gson);
      }

      return content;
    } catch (Exception e) {
      log.error(AppLogMessage.message("#Masking - json error {}", e.getMessage()).error(e));
      return "not a json";
    }
  }

  private static void maskArray(JsonArray jsonArray, String targetKey) {
    for (int i=0; i<jsonArray.size(); i++) {
      maskObject((JsonObject) jsonArray.get(i), targetKey);
    }
  }

  private static void maskObject(JsonObject jsonObject, String targetKey) {
    for (String key : jsonObject.keySet()) {
      Object value = jsonObject.get(key);

      if (value instanceof JsonObject jsonObj) {
        maskObject(jsonObj, targetKey);
      } else if (value instanceof JsonArray jsonArray) {
        maskArray(jsonArray, targetKey);
      } else if (key.equals(targetKey)) {
        jsonObject.addProperty(key, masking(jsonObject.get(key).getAsString()));
      }
    }
  }
}
