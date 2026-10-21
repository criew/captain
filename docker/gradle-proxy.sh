#!/bin/sh
# Uebersetzt HTTP(S)_PROXY/NO_PROXY (Build-Argumente, z. B. aus ~/.docker/config.json)
# in gradle.properties – Gradle liest die Umgebungsvariablen nicht selbst.
set -e
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$gradle_home"
props="$gradle_home/gradle.properties"
: > "$props"

for scheme in http https; do
  if [ "$scheme" = http ]; then url="${HTTP_PROXY:-$http_proxy}"; else url="${HTTPS_PROXY:-$https_proxy}"; fi
  [ -n "$url" ] || continue
  hostport="${url#*://}"
  hostport="${hostport%%/*}"
  hostport="${hostport##*@}"
  host="${hostport%:*}"
  port="${hostport##*:}"
  [ "$port" != "$hostport" ] || port=80
  echo "systemProp.$scheme.proxyHost=$host" >> "$props"
  echo "systemProp.$scheme.proxyPort=$port" >> "$props"
done

noproxy="${NO_PROXY:-$no_proxy}"
if [ -n "$noproxy" ]; then
  # a.example,.b.example → a.example|*.b.example
  hosts="$(echo "$noproxy" | tr -d ' ' | sed 's/,/|/g; s/|\./|*./g; s/^\./*./')"
  echo "systemProp.http.nonProxyHosts=$hosts" >> "$props"
  echo "systemProp.https.nonProxyHosts=$hosts" >> "$props"
fi
