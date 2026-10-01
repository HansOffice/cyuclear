#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."
VER=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
NAME=$(sed -n 's:.*<artifactId>\(.*\)</artifactId>.*:\1:p' pom.xml | head -1)
for p in paper folia legacy; do
  mvn -o -q -P "$p" package -DskipTests
done
python3 - "$NAME" "$VER" <<'EOF'
import sys, zipfile, os
name, ver = sys.argv[1], sys.argv[2]
out = f"发行/{name}-{ver}.zip"
if os.path.exists(out):
    os.remove(out)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for plat in ["paper", "folia", "legacy"]:
        jar = f"{name}-{plat}-{ver}.jar"
        z.write(f"target/{jar}", jar)
print(f"发行完成: {out}")
EOF
