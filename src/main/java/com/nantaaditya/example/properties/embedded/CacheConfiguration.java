package com.nantaaditya.example.properties.embedded;

import com.nantaaditya.example.model.constant.CacheType;
import java.util.concurrent.TimeUnit;

public record CacheConfiguration(
    CacheType type,
    long size,
    int timeOut,
    TimeUnit timeUnit
) {

}
