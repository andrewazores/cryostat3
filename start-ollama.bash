#!/usr/bin/env bash

PORT="${OLLAMA_PORT:-11434}"

podman run --rm -it --replace \
    --pull always \
    -p "${PORT}:${PORT}" \
    -v "${OLLAMA_VOLUME_NAME:-ollama}":/root/.ollama \
    --name "${OLLAMA_CONTAINER_NAME:-ollama}" \
    ollama
