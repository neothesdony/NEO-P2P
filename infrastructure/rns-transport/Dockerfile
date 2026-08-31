# NEO-P2P RNS Transport Node
#
# Runs rnsd-kt (the Kotlin Reticulum daemon) with enableTransport=true and a
# TCP server interface. Phones connect as TCP clients; the node routes
# announces, paths, and links between peers and to the LXMF propagation node.
#
# Build: the rnsd-kt fat jar is produced from ~/reticulum-kt:
#   cd ~/reticulum-kt && JAVA_HOME=/home/thesdony/.sdkman/candidates/java/21.0.3-tem \
#     ./gradlew :rns-cli:shadowJar
#   cp rns-cli/build/libs/rnsd-kt.jar infrastructure/rns-transport/rnsd-kt.jar
#
# The jar is NOT committed (built artifact); deploy.sh copies it from the
# build machine or the image is built on the VPS with the jar bind-mounted.

FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/rnsd

COPY rnsd-kt.jar /opt/rnsd/rnsd-kt.jar

# Config dir is mounted from the host (config + storage persist across restarts).
VOLUME /etc/reticulum

EXPOSE 42000

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -sf --max-time 5 http://127.0.0.1:42000/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["java", "-jar", "/opt/rnsd/rnsd-kt.jar", "--config", "/etc/reticulum", "-v"]
