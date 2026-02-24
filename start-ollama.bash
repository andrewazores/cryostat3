#!/usr/bin/env bash

PORT="${OLLAMA_PORT:-11434}"

podman run --rm -it --replace \
    --pull always \
    --name "${OLLAMA_CONTAINER_NAME:-ollama}" \
    -p "${PORT}:${PORT}" \
    -v "${OLLAMA_VOLUME_NAME:-ollama}":/root/.ollama \
    --cpus "${OLLAMA_CPU_LIMIT:-8.0}" \
    --memory "${OLLAMA_MEM_LIMIT:-16GiB}" \
    ollama
