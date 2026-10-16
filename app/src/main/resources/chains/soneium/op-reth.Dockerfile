# syntax=docker/dockerfile:1
# Builds op-reth from the ethereum-optimism/optimism monorepo at TAG (e.g. op-reth/v2.6.0) for `clients.yml` → build.
# Ubuntu 22.04 keeps the glibc requirement of the binary low, so it runs on most hosts.
FROM ubuntu:22.04 AS build
ARG VERSION
ARG TAG
LABEL org.opencontainers.image.version=$VERSION
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential clang libclang-dev pkg-config cmake git curl ca-certificates openssl zstd xz-utils \
 && rm -rf /var/lib/apt/lists/*

# The toolchain version comes from the repository's rust-toolchain.toml (installed on the first cargo call).
ENV RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo PATH=/opt/cargo/bin:$PATH
RUN curl -fsSL --retry 8 --retry-all-errors https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain none --no-modify-path

# op-reth's chainspec build.rs needs the superchain-registry submodule.
RUN git clone --depth 1 --branch "$TAG" https://github.com/ethereum-optimism/optimism.git /src \
 && git -C /src submodule update --init --depth 1 superchain-registry

WORKDIR /src/rust
RUN ram_gb=$(awk '/MemTotal/ { printf "%d", $2 / 1048576 }' /proc/meminfo); \
    jobs=$(( ram_gb / 3 )); [ "$jobs" -lt 1 ] && jobs=1; [ "$jobs" -gt "$(nproc)" ] && jobs=$(nproc); \
    cargo build --release --bin op-reth -p op-reth -j "$jobs"

FROM scratch
COPY --from=build /src/rust/target/release/op-reth /op-reth
