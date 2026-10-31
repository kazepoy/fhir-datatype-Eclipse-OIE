# syntax=docker/dockerfile:1.7
# Image de la démo : Open Integration Engine 4.7.0, l'administration web (Web Support) et ce
# plugin FHIR, compilé ici depuis les sources du dépôt. Aucun autre plugin.
#
# Contexte de build : la racine du dépôt (voir compose.yaml), pour avoir pom.xml, src/ et webadmin/.
# L'image OIE de base n'est pas construite ici : le service oie-engine de compose.yaml la
# construit depuis le dépôt officiel et la transmet sous le nom oie-engine (additional_contexts).
# Ce Dockerfile ne se construit donc que par docker compose build.

ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
# Figée par digest ; BusyBox fournit unzip.
ARG BUSYBOX_IMAGE=busybox:1.37.0-glibc@sha256:42a9ff19af33811ee466b24c66384461d37c32dcc28d1d27d49b8aca816bd5ee

# --- Le plugin FHIR, depuis les sources. Les jars du moteur, absents de Maven Central, sont pris
# dans l'image OIE elle-même : la version compilée est exactement celle qui l'exécute.
FROM ${MAVEN_IMAGE} AS plugin
WORKDIR /build
COPY --from=oie-engine /opt/engine/server-lib/mirth-server.jar /engine/server-api.jar
COPY --from=oie-engine /opt/engine/server-lib/donkey/donkey-server.jar /engine/donkey-server.jar
COPY --from=oie-engine /opt/engine/server-lib/donkey/donkey-model.jar /engine/donkey-model.jar
COPY --from=oie-engine /opt/engine/client-lib/mirth-client.jar /engine/client.jar
COPY --from=oie-engine /opt/engine/server-lib/mirth-client-core.jar /engine/client-core.jar
RUN --mount=type=cache,target=/root/.m2 \
    for jar in server-api donkey-server donkey-model client client-core; do \
      mvn -q -B install:install-file -Dfile=/engine/$jar.jar -DgroupId=com.mirth.connect \
        -DartifactId=$jar -Dversion=4.7.0 -Dpackaging=jar || exit 1; \
    done
COPY pom.xml assembly.xml ./
COPY src ./src
COPY webadmin ./webadmin
# Les tests tournent avec mvn package hors Docker ; ici, l'image seulement.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests \
    && mkdir /extensions && cd /extensions && jar xf /build/target/datatype-fhir-*.zip

# --- Web Support 1.2.1 : l'administration web embarquée. URL et SHA-256 fixes.
FROM ${BUSYBOX_IMAGE} AS websupport
ADD --checksum=sha256:232be8d881073f8090f7e7d442dd7fcf78f58447f9251eee153acba9d9392560 \
    https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.2.1/websupport-1.2.1.zip /downloads/websupport.zip
# Extrait au build plutôt que déposé dans custom-extensions/ : configure-from-env le
# décompresserait à chaque démarrage du conteneur.
RUN mkdir -p /extensions && unzip -q /downloads/websupport.zip -d /extensions

FROM oie-engine
COPY --from=websupport --chown=engine:engine /extensions/ /opt/engine/extensions/
COPY --from=plugin --chown=engine:engine /extensions/ /opt/engine/extensions/
LABEL org.opencontainers.image.title="OIE 4.7.0, administration web et FHIR Profile Mapper (démo ORU vers FHIR)"
