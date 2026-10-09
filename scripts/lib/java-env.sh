# Sourced by the build/release scripts. On Windows (Git Bash) neither JAVA_HOME nor `java` is
# usually set, so ./gradlew fails before it can pick the JDK 26 toolchain. Find a JDK the same
# places IntelliJ/Gradle put them. No-op when JAVA_HOME is already valid or on Linux/macOS with java on PATH.

rpcnode_is_windows_shell() {
  case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

rpcnode_valid_java_home() {
  [ -n "${1:-}" ] && { [ -x "$1/bin/java" ] || [ -x "$1/bin/java.exe" ]; }
}

rpcnode_find_jdk() {
  local root dir
  for root in \
    "$HOME/.jdks" \
    "$HOME/.gradle/jdks" \
    "$HOME/.sdkman/candidates/java" \
    /usr/lib/jvm \
    "${ProgramFiles:-/c/Program Files}/Java" \
    "${ProgramFiles:-/c/Program Files}/Eclipse Adoptium" \
    "${ProgramFiles:-/c/Program Files}/Microsoft" \
    "${ProgramFiles:-/c/Program Files}/Zulu"
  do
    [ -d "$root" ] || continue
    # newest first: the build wants the highest JDK (toolchain 26)
    while IFS= read -r dir; do
      if rpcnode_valid_java_home "$dir"; then
        printf '%s\n' "$dir"
        return 0
      fi
    done < <(find "$root" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -rV)
  done
  return 1
}

rpcnode_setup_java() {
  if rpcnode_valid_java_home "${JAVA_HOME:-}"; then
    return 0
  fi
  if ! rpcnode_is_windows_shell && command -v java >/dev/null 2>&1; then
    return 0
  fi
  local found
  if found="$(rpcnode_find_jdk)"; then
    export JAVA_HOME="$found"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "using JDK: $JAVA_HOME"
    return 0
  fi
  echo "no JDK found: set JAVA_HOME to a JDK 26 (e.g. ~/.jdks/temurin-26)" >&2
  return 1
}

rpcnode_setup_java
