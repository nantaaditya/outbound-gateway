package com.nantaaditya.example.properties.embedded;

public record ClientTimeOutConfiguration(
    int connectTimeOut,
    int writeTimeOut,
    int readTimeOut,
    int responseTimeOut
) {

}
