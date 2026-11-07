FROM bellsoft/liberica-runtime-container:jdk-all-25-musl AS jre-build

WORKDIR /build

# Spring Boot builds one executable (fat) jar. `jarmode=tools extract` splits it into a thin app jar + lib/*.jar,
# which is what jdeps needs and what the classpath-style entrypoint below expects.
COPY target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted

# Dynamically extract necessary modules instead of using ALL-MODULE-PATH
RUN MODULES=$(jdeps --ignore-missing-deps -q --recursive --multi-release 25 --print-module-deps --class-path 'extracted/lib/*' extracted/app.jar) && \
    echo "${MODULES},jdk.crypto.ec,jdk.charsets,java.naming,jdk.unsupported,java.management,jdk.management" > jdeps.info

# Build a truly minimized custom runtime
RUN jlink \
    --add-modules $(cat jdeps.info | tr -d '\n') \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /javaruntime

RUN strip -p --strip-unneeded /javaruntime/lib/server/libjvm.so && \
    find /javaruntime -name '*.so' | xargs -i strip -p --strip-unneeded {}

# ---------------------------------------------------------
# FINAL RUNTIME IMAGE
# ---------------------------------------------------------
FROM bellsoft/alpaquita-linux-base:latest

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

ENV TZ=Asia/Jakarta

# Let Kubernetes dictate the physical limits, let Java take 75% of it.
# (85% leaves too little for metaspace, thread stacks and Netty direct buffers, which this WebFlux app uses.)
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=80.0 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=60 -XX:InitiatingHeapOccupancyPercent=40 -XX:+UseCompressedOops -XX:+UseCompressedClassPointers -XX:+UseContainerSupport -XX:+UseG1GC -XX:+OptimizeStringConcat"

LABEL org.opencontainers.image.authors="core-team"

# Run as a non-root user; /app/logs must be writable (LOG_PATH defaults to logs/)
RUN apk update && apk add --no-cache procps tzdata && \
    addgroup -S app && adduser -S -G app -h /app app && \
    mkdir -p /app/logs /app/lib /app/patch /app/resources /app/resources-patch && \
    chown -R app:app /app

WORKDIR /app

# Ordered copying for optimal layer caching: dependencies (rarely change) -> config -> app jar (changes every build)
COPY --from=jre-build --chown=app:app /build/extracted/lib/ lib/
COPY --chown=app:app src/main/resources resources/
COPY --from=jre-build --chown=app:app /build/extracted/app.jar app.jar
# MavenHelper reads ./pom.xml at runtime
COPY --chown=app:app pom.xml pom.xml

USER app

# 8080 = API, 1000 = actuator (see SERVER_PORT / ACTUATOR_PORT)
EXPOSE 8080 1000

# Using the exec form to ensure SIGTERM reaches the JVM.
# resources/ and patch/ come first so they can override what is packaged in the jars.
ENTRYPOINT ["java", "-cp", ".:resources:patch/*:app.jar:lib/*", "com.nantaaditya.example.OutboxGatewayApplication"]
