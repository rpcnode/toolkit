# syntax=docker/dockerfile:1
# Builds xrpld from the XRPLF/rippled release TAG for `clients.yml` → build (panel side, once per release).
# Build: BUILD.md of XRPLF/rippled — Conan 2 + CMake + Clang 22 (the compiler CI tests), Release, xrpld only.
# libstdc++/libgcc are linked statically so the binary does not need the build image's GCC 14 runtime on the host; the
# host still needs glibc >= 2.39 (Ubuntu 24.04+, Debian 13+) — run.sh falls back to compiling locally when it cannot run.
FROM ubuntu:24.04 AS build
ARG VERSION
ARG TAG
ARG CLANG_MAJOR=22
ARG CONAN_VERSION=2.33.0
ARG TARGETARCH
LABEL org.opencontainers.image.version=$VERSION
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential git curl ca-certificates cmake ninja-build pkg-config python3 python3-venv python3-pip ccache \
      xz-utils lsb-release gnupg libstdc++-14-dev patchelf \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /etc/apt/keyrings \
 && curl -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key -o /etc/apt/keyrings/llvm.asc \
 && printf 'Types: deb\nURIs: https://apt.llvm.org/noble/\nSuites: llvm-toolchain-noble-%s\nComponents: main\nSigned-By: /etc/apt/keyrings/llvm.asc\n' "$CLANG_MAJOR" \
      > /etc/apt/sources.list.d/llvm.sources \
 && apt-get update && apt-get install -y --no-install-recommends "clang-$CLANG_MAJOR" \
 && ln -sf "/usr/bin/clang-$CLANG_MAJOR" /usr/bin/clang && ln -sf "/usr/bin/clang++-$CLANG_MAJOR" /usr/bin/clang++ \
 && rm -rf /var/lib/apt/lists/*

# Conan 2 (rippled needs >= 2.17): the standalone build from GitHub first (no Python, no PyPI — PyPI is unreachable from
# some hosts), pip from PyPI as the fallback. Either way it ends up as /opt/conan/bin/conan.
RUN set -eu; \
    case "${TARGETARCH:-amd64}" in arm64) a=aarch64 ;; *) a=x86_64 ;; esac; \
    mkdir -p /opt/conan; \
    if curl -fsSL --retry 5 --retry-all-errors "https://github.com/conan-io/conan/releases/download/${CONAN_VERSION}/conan-${CONAN_VERSION}-linux-${a}.tgz" | tar -xz -C /opt/conan; then \
      echo "conan: standalone ${CONAN_VERSION}"; \
    else \
      echo "conan: standalone download failed, trying pip"; rm -rf /opt/conan; \
      python3 -m venv /opt/conan && /opt/conan/bin/pip install --retries 10 --timeout 60 'conan>=2.17'; \
    fi; \
    /opt/conan/bin/conan --version
ENV PATH=/opt/conan/bin:$PATH CONAN_HOME=/opt/conan-home CC=clang CXX=clang++

# Runs a long build step quietly: the full output goes to /tmp/<name>.log, the progress lines (one per Conan package / CMake
# target) are shown live, and on failure the FIRST compiler errors with their context are printed. Without this the useful
# lines are lost: BuildKit clips a step's log at 2 MiB and the end of a failed build is only `gmake: *** Error 2`.
COPY --chmod=755 <<'EOF' /usr/local/bin/run-logged
#!/bin/bash
name=$1; shift
log=/tmp/$name.log
"$@" 2>&1 | tee "$log" | grep --line-buffered -E 'Calling build\(\)|: Package .* (built|build failed)|^-+ (Installing|Computing)|\[ *[0-9]+%\] (Linking|Built target)|error:|ERROR' | cut -c1-300
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ]; then
  echo "===== $name failed (exit $rc) — first errors with context ====="
  grep -n -B4 -A14 -m6 -E 'error:|Error:|^ERROR' "$log" | cut -c1-400
  echo "===== $name: last 40 lines ====="
  tail -40 "$log" | cut -c1-400
fi
exit "$rc"
EOF

RUN git clone --depth 1 --branch "$TAG" https://github.com/XRPLF/rippled.git /src
WORKDIR /src
RUN ./conan/init.sh
WORKDIR /src/.build
# `--profile:all ci` is rippled's own CI profile (conan/profiles/ci): it pins user.package:libc_version=2.31 into the package
# id, which is how the binaries on the xrplf remote (clang 22 / libstdc++11 / C++23, built by their CI) are named. Without it
# Conan finds none of them and compiles every dependency here — hours, and grpc does not even build with this clang +
# libstdc++. `--build missing` stays as the fallback for any package the remote lacks.
RUN run-logged conan conan install .. --output-folder . --build missing --profile:all ci --settings:all build_type=Release
# The executables inside those prebuilt packages (protoc, grpc_cpp_plugin, …) are Nix builds: their ELF interpreter and
# RPATH point into /nix/store, which does not exist here ("Dynamic loader not found"), and the first thing the build runs is
# protoc. Point them at the system loader and libraries; glibc 2.31 builds run fine on the newer one of this image.
# A tool that cannot be re-linked is reported, not fatal: only protoc and grpc_cpp_plugin must run, and when one does not
# the step prints why (ldd, interpreter) instead of a bare exit code.
COPY --chmod=755 <<'EOF' /usr/local/bin/relink-nix-tools
#!/bin/bash
set -u
case "$(uname -m)" in
  x86_64) ld=/lib64/ld-linux-x86-64.so.2 ;;
  aarch64) ld=/lib/ld-linux-aarch64.so.1 ;;
  *) echo "unsupported CPU $(uname -m)"; exit 1 ;;
esac
n=0; bad=0
while IFS= read -r -d '' f; do
  interp="$(patchelf --print-interpreter "$f" 2>/dev/null)" || continue
  case "$interp" in /nix/store/*) ;; *) continue ;; esac
  chmod u+w "$f" 2>/dev/null
  if out="$(patchelf --set-interpreter "$ld" --set-rpath '$ORIGIN/../lib' "$f" 2>&1)"; then
    n=$((n+1))
  else
    bad=$((bad+1)); echo "could not re-link $f: $out"
  fi
done < <(find /opt/conan-home/p -type f -perm -u+x -print0)
echo "re-linked $n Nix-built tools to $ld ($bad failed)"
status=0
# exact names: protoc-* would also match the protoc-gen-upb* plugins, and find's order differs between filesystems
for tool in 'protoc-[0-9]*' grpc_cpp_plugin; do
  f="$(find /opt/conan-home/p -path "*/p/bin/$tool" -type f | sort | head -n1)"
  if [ -z "$f" ]; then
    echo "missing $tool in the Conan packages:"; ls /opt/conan-home/p; status=1; continue
  fi
  "$f" --version </dev/null >/dev/null 2>&1; rc=$?
  if [ "$rc" -ge 126 ]; then
    echo "does not run (exit $rc): $f"; "$f" --version </dev/null 2>&1 | head -5
    readelf -l "$f" | grep -i interpreter; ldd "$f" 2>&1 | head -20; status=1
  else
    echo "ok: $f"
  fi
done
exit "$status"
EOF
RUN relink-nix-tools

RUN run-logged cmake-configure cmake -DCMAKE_TOOLCHAIN_FILE:FILEPATH=build/generators/conan_toolchain.cmake -DCMAKE_BUILD_TYPE=Release \
      -Dxrpld=ON -Dtests=OFF -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++ -static-libgcc" ..
RUN ram_gb=$(awk '/MemTotal/ { printf "%d", $2 / 1048576 }' /proc/meminfo); \
    jobs=$(( ram_gb / 4 )); [ "$jobs" -lt 1 ] && jobs=1; [ "$jobs" -gt "$(nproc)" ] && jobs=$(nproc); \
    run-logged build cmake --build . --parallel "$jobs" --target xrpld

# Debug info makes the binary ~7x bigger (419 MB) and it is copied to the panel and on to every host: strip it. A separate
# step, so a change here never re-runs the hours of compilation above.
RUN strip --strip-unneeded /src/.build/xrpld && /src/.build/xrpld --version

FROM scratch
COPY --from=build /src/.build/xrpld /xrpld
