#!/bin/bash
# Stand-in for psql in TonIndexerClusterTest: logs every call, answers the login check, and remembers which roles / databases /
# tablespaces "exist" (type-prefixed: a role and a database may share a name) so existence checks behave like the real thing.
echo "psql $*" >> '__CALLS__'
mem='__ROOT__/pgobjects'
touch "$mem"
for a in "$@"; do
  case "$a" in postgresql://*) echo 1; exit 0 ;; esac
done
for a in "$@"; do
  if [[ "$a" =~ ^select\ 1\ from\ pg_(roles|database|tablespace)\ where\ [a-z]+\ =\ \'([a-z0-9_]+)\'$ ]]; then
    kind="${BASH_REMATCH[1]}"; name="${BASH_REMATCH[2]}"
    case "$kind" in roles) kind=role ;; database) kind=db ;; tablespace) kind=ts ;; esac
    grep -qx "$kind:$name" "$mem" && echo 1
  fi
  if [[ "$a" =~ ^create\ (role|database|tablespace)\ ([a-z0-9_]+) ]]; then
    kind="${BASH_REMATCH[1]}"; name="${BASH_REMATCH[2]}"
    case "$kind" in database) kind=db ;; tablespace) kind=ts ;; esac
    echo "$kind:$name" >> "$mem"
  fi
done
exit 0
