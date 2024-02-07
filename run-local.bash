#!/usr/bin/env bash

set -xe

function cleanup() {
    set +xe
    podman stop cryostat3-local-db
    podman stop cryostat3-local-storage
}
trap cleanup EXIT

podman run --rm -d \
    --name cryostat3-local-db \
    --publish 5432:5432 \
    --env PG_ENCRYPT_KEY=abcd1234 \
    --env POSTGRESQL_USER=cryostat3 \
    --env POSTGRESQL_PASSWORD=cryostat3 \
    --env POSTGRESQL_DATABASE=cryostat3 \
    quay.io/cryostat/cryostat-db:latest

podman run --rm -d \
    --name cryostat3-local-storage \
    --publish 4566:4566 \
    --env SERVICES=s3 \
    --env START_WEB=1 \
    --env PORT_WEB_UI=4577 \
    --env DEFAULT_REGION=us-east-1 \
    docker.io/localstack/localstack:latest

CRYOSTAT_DISCOVERY_JDP_ENABLED=true \
    QUARKUS_S3_AWS_REGION=us-east-1 \
    QUARKUS_S3_ENDPOINT_OVERRIDE=http://localhost:4566 \
    QUARKUS_S3_PATH_STYLE_ACCESS=true \
    QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION=drop-and-create \
    QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/cryostat3 \
    QUARKUS_DATASOURCE_USERNAME=cryostat3 \
    QUARKUS_DATASOURCE_PASSWORD=cryostat3 \
    java -jar target/quarkus-app/quarkus-run.jar \
    -Dcom.sun.management.jmxremote.autodiscovery=true \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=9091 \
    -Dcom.sun.management.jmxremote.rmi.port=9091 \
    -Djava.rmi.server.hostname=127.0.0.1 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.local.only=false
