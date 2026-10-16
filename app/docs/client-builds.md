# Compiled clients (`build:` in `clients.yml`)

Some clients ship no usable binary (XRPL `xrpld`, Solana `agave-validator` since v3.0, Optimism `op-reth` / `op-node`). Instead of every host compiling
the source, the panel has **host agents compile it once per CPU architecture** and serves the result like any other
client file.

## Declaring a build

```yaml
programs:
  - env: mainnet
    id: xrpld
    source: { type: github, repo: XRPLF/rippled }
    build: { dockerfile: xrpld.Dockerfile, outputs: [xrpld], platforms: [linux/amd64, linux/arm64] }
    artifacts: [ … source archive … ]   # stays: fallback for hosts that cannot run the binary
```

`xrpld.Dockerfile` sits beside `clients.yml`. It receives `VERSION` and `TAG` build args and its last stage
(`FROM scratch`) puts every entry of `outputs` at the image root. `platforms` is what the program can be built for.

## What happens

| When | What |
| --- | --- |
| A release is added / updated (Clients page) | Compiled for every architecture the **enrolled servers** run (`x86_64`, `aarch64`). The pin moves only when the build succeeded; a failed build keeps the previous release. |
| A node is created (Clients step) on a host whose architecture is not stored yet | The panel starts the build in the background and answers `202 client_building`; the wizard retries every 20 s until it is ready. Same for "Update client" on an existing node. |
| No online agent of that architecture can build (no Docker, busy, old agent) | The source is published as before and the host compiles it itself (`run.sh.tmpl` / `SolanaAgaveBins`). |

Builds run on the **strongest, least loaded** online agent of the architecture (`ServerBuildHostDirectory`), one compile
per host at a time, natively (no emulation). The panel's own Docker is only a last resort for its own architecture.

## Files and protocol

* Stored per architecture on the panel as `<output>-<arch>` (`xrpld-x86_64`, `xrpld-aarch64`) next to the source and
  listed in `install-plan.yml` with that `arch`, so each host's agent downloads only its own file. The Clients page shows
  them behind the CPU icon of a row (`arches` in `GET /api/clients`).
* A new release removes the previous release's binaries; adding an architecture to the *same* release keeps the others.
* Panel → agent (`/api/v1/client/build`, `…/progress`, `…/artifact`, `…/release`): the panel sends the Dockerfile text, the
  agent runs `docker build --platform … --output type=local` and keeps the files until the panel fetched them. The job id
  is stable per `(network, env, program, tag, arch)`, so a restarted panel re-attaches to a running build.
* Hosts: XRPL runs `xrpld-<arch>` when `--version` works there (glibc!) and compiles the source otherwise; Solana's
  `SolanaAgaveBins` prefers `agave-validator-<arch>` over a local cargo build.

## Pinned pairs (Optimism)

`op-reth` and `op-node` must match each other, so `chains/optimism/clients.yml` pins both tags (`source: pinned`), the same
ones as `OptimismUnitBodies.RETH_TAG` / `NODE_TAG` (a test keeps them equal). The panel builds exactly those tags;
bumping the pair means editing both places. Optimism is therefore an ordinary client on the Clients page (not "pin only")
and its install step compiles the missing architecture like XRPL / Solana do.

The OP Stack family (unichain, ink, soneium, lisk, mode, zora; World Chain only for op-node) pins the **same pair** in each
`chains/<id>/clients.yml`, equal to `OpstackToolchain.RETH_TAG` / `NODE_TAG` (`OpstackCatalogTest`). The two Dockerfiles are copied into every
network folder (the loader resolves `build.dockerfile` beside `clients.yml`), so each network × env is compiled by its own build job.

## Requirements

* Docker on at least one agent per architecture you run. The agent does not install it by itself.
* `xrpld.Dockerfile` builds on Ubuntu 24.04 with clang 22 and libstdc++ linked statically; the binary needs glibc >= 2.38
  (Ubuntu 24.04+, Debian 13+) and is stripped (about 65 MB). Older hosts refuse to start it, `run.sh` notices (`--version`)
  and compiles on the host instead.

## Notes on the XRPL build

* Dependencies come **prebuilt** from `conan.xrplf.org`: `conan install --profile:all ci` uses rippled's own CI profile, which
  pins `user.package:libc_version=2.31` into the package id (that is how their binaries are named). Without it nothing
  matches and Conan compiles every dependency (hours, and grpc 1.81 does not build with clang 22 + libstdc++ 14).
* The tools inside those packages (`protoc`, `grpc_cpp_plugin`, …) are Nix builds with `/nix/store` baked in as ELF loader
  and RPATH; the Dockerfile re-links them to the system loader with `patchelf` before the build runs.
* `run-logged` (installed in the image) keeps the full output in a file, shows progress lines and, on failure, prints the
  first compiler errors: BuildKit clips a step's log at 2 MiB, so a plain `RUN` loses the interesting part.
* Agents must be updated to a version that has the build endpoints; older agents answer 404 and are skipped.
