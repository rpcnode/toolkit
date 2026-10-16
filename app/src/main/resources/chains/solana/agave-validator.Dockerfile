# syntax=docker/dockerfile:1
# Builds agave-validator from the Anza source tag TAG (the release tarball stopped shipping it with Agave v3.0), for
# `clients.yml` → build. Ubuntu 22.04 keeps the glibc requirement of the binary low, so it runs on most hosts.
FROM ubuntu:22.04 AS build
ARG VERSION
ARG TAG
LABEL org.opencontainers.image.version=$VERSION
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates bzip2 git build-essential pkg-config libudev-dev llvm libclang-dev clang cmake \
      protobuf-compiler libssl-dev libprotobuf-dev \
 && rm -rf /var/lib/apt/lists/*

ENV RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo PATH=/opt/cargo/bin:$PATH
RUN curl --proto '=https' --tlsv1.2 -sSf --retry 8 --retry-delay 3 --retry-all-errors https://sh.rustup.rs \
      | sh -s -- -y --default-toolchain stable --profile minimal

RUN git clone --depth 1 --branch "$TAG" https://github.com/anza-xyz/agave.git /src
WORKDIR /src
RUN cargo build --release --bin agave-validator

FROM scratch
COPY --from=build /src/target/release/agave-validator /agave-validator
