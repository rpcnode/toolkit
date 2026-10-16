# syntax=docker/dockerfile:1
# Builds peaq-node from peaqnetwork/peaq-network-node at TAG (peaq-v0.0.113 / peaq-dev-v0.0.109) for `clients.yml` → build.
# Mirrors the upstream scripts/Dockerfile build (`cargo build --release --features on-chain-release-build`) but on a plain
# Ubuntu 22.04 base (low glibc requirement) — the upstream builder image paritytech/ci-linux is not needed.
# rust-toolchain at the tag pins 1.82.0 + wasm32-unknown-unknown + rust-src: rustup installs them on the first cargo call.
FROM ubuntu:22.04 AS build
ARG VERSION
ARG TAG
LABEL org.opencontainers.image.version=$VERSION
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential clang libclang-dev lld pkg-config cmake git curl ca-certificates libssl-dev protobuf-compiler \
 && rm -rf /var/lib/apt/lists/*

ENV RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo PATH=/opt/cargo/bin:$PATH
RUN curl -fsSL --retry 8 --retry-all-errors https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain none --no-modify-path

RUN git clone --depth 1 --branch "$TAG" https://github.com/peaqnetwork/peaq-network-node.git /src
WORKDIR /src
# Substrate runtimes need ~3-4 GB of RAM per rustc job: bound -j by memory.
RUN ram_gb=$(awk '/MemTotal/ { printf "%d", $2 / 1048576 }' /proc/meminfo); \
    jobs=$(( ram_gb / 4 )); [ "$jobs" -lt 1 ] && jobs=1; [ "$jobs" -gt "$(nproc)" ] && jobs=$(nproc); \
    cargo build --release --features on-chain-release-build -j "$jobs" --bin peaq-node

FROM scratch
COPY --from=build /src/target/release/peaq-node /peaq-node
