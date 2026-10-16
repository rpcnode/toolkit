# syntax=docker/dockerfile:1
# Builds snarkos from ProvableHQ/snarkOS at TAG (testnet-v4.12.0 …) for `clients.yml` → build (testnet env only; mainnet uses
# the official release zip). Same steps as upstream's release.yml: apt lld libclang-dev, `cargo build --all --release`, strip.
FROM ubuntu:22.04 AS build
ARG VERSION
ARG TAG
LABEL org.opencontainers.image.version=$VERSION
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential clang libclang-dev lld pkg-config cmake git curl ca-certificates libssl-dev \
 && rm -rf /var/lib/apt/lists/*

ENV RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo PATH=/opt/cargo/bin:$PATH
RUN curl -fsSL --retry 8 --retry-all-errors https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable --no-modify-path

RUN git clone --depth 1 --branch "$TAG" https://github.com/ProvableHQ/snarkOS.git /src
WORKDIR /src
RUN ram_gb=$(awk '/MemTotal/ { printf "%d", $2 / 1048576 }' /proc/meminfo); \
    jobs=$(( ram_gb / 3 )); [ "$jobs" -lt 1 ] && jobs=1; [ "$jobs" -gt "$(nproc)" ] && jobs=$(nproc); \
    cargo build --release -j "$jobs" && strip target/release/snarkos

FROM scratch
COPY --from=build /src/target/release/snarkos /snarkos
