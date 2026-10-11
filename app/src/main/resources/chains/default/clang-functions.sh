# Shared shell functions: make `clang` on this host the requested major version (apt-based systems).
# Used by the host-deps step (agent) and, as a safety net, by chains/ton/bootstrap.sh.tmpl.
#
# apt.llvm.org only publishes suites for Ubuntu/Debian codenames. Every apt-based system is some Ubuntu or Debian release
# underneath (Linux Mint "zena" = Ubuntu noble, LMDE "faye" = Debian bookworm, Pop!_OS, Zorin, Raspberry Pi OS, Kali,
# non-LTS Ubuntu, Debian 13 ...), so the LLVM repository of that base release is used, with fallbacks.

clang_major_of() {
  "$1" --version 2>/dev/null | head -n1 | sed -n 's/.*version[[:space:]]*\([0-9][0-9]*\).*/\1/p'
}

os_release_get() { ( . "${OS_RELEASE_FILE:-/etc/os-release}" 2>/dev/null; eval "printf '%s' \"\${$1:-}\"" ); }

# Prints "<family> <base codename>" (family: ubuntu | debian), nothing when the system is not apt-based.
os_base_release() {
  local base like
  base="$(os_release_get UBUNTU_CODENAME)"
  if [[ -n "$base" ]]; then echo "ubuntu $base"; return 0; fi
  base="$(os_release_get DEBIAN_CODENAME)"
  if [[ -n "$base" ]]; then echo "debian $base"; return 0; fi
  like="$(os_release_get ID) $(os_release_get ID_LIKE)"
  base="$(os_release_get VERSION_CODENAME)"
  case " $like " in
    *" ubuntu "*) [[ -n "$base" ]] && echo "ubuntu $base" ;;
    *" debian "*) [[ -n "$base" ]] && echo "debian $base" ;;
  esac
  return 0
}

# ensure_clang_major <major>: installs clang-<major> from apt.llvm.org and makes it the default `clang`.
ensure_clang_major() {
  local major="$1" family base repo cand
  if [[ "$(clang_major_of clang)" == "$major" ]]; then return 0; fi
  if ! command -v apt-get >/dev/null 2>&1; then
    echo "ERROR: clang $major needs an apt-based system (Debian/Ubuntu and derivatives)" >&2
    return 1
  fi
  read -r family base < <(os_base_release) || true
  if [[ -z "${base:-}" ]]; then
    echo "ERROR: cannot tell which Ubuntu/Debian release this system is based on (see /etc/os-release)" >&2
    return 1
  fi
  echo "installing clang-$major from apt.llvm.org ($family '$base')"
  repo=""
  local -a cands=("$base")
  if [[ "$family" = ubuntu ]]; then cands+=(noble jammy); else cands+=(trixie bookworm); fi
  for cand in "${cands[@]}"; do
    if curl -fsSL --max-time 30 -o /dev/null "https://apt.llvm.org/${cand}/dists/llvm-toolchain-${cand}-${major}/Release"; then
      repo="$cand"; break
    fi
  done
  [[ -n "$repo" ]] || { echo "ERROR: no apt.llvm.org clang-$major suite for '$base' (tried ${cands[*]})" >&2; return 1; }
  [[ "$repo" = "$base" ]] || echo "LLVM suite for '$base' is unavailable; using '$repo'"
  export DEBIAN_FRONTEND=noninteractive
  mkdir -p /etc/apt/keyrings
  curl -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key -o /etc/apt/keyrings/llvm.asc
  cat >/etc/apt/sources.list.d/llvm.sources <<LLVM
Types: deb
URIs: https://apt.llvm.org/${repo}/
Suites: llvm-toolchain-${repo}-${major}
Components: main
Signed-By: /etc/apt/keyrings/llvm.asc
LLVM
  apt-get update
  apt-get install -y "clang-$major"
  update-alternatives --install /usr/bin/clang clang "/usr/bin/clang-$major" 200
  update-alternatives --install /usr/bin/clang++ clang++ "/usr/bin/clang++-$major" 200
  ln -sf "/usr/bin/clang-$major" /usr/bin/clang
  ln -sf "/usr/bin/clang++-$major" /usr/bin/clang++
  [[ "$(clang_major_of clang)" == "$major" ]] || { echo "ERROR: clang is not version $major after the install" >&2; return 1; }
}
