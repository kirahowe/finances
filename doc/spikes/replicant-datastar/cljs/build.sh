#!/usr/bin/env bash
# Build the CLJS island to an advanced-optimized single file with cljs.main.
# (A real project would likely use shadow-cljs for better DX + npm interop; this
# needs neither, since the only JS dep — combobox-framework — is vendored and
# loaded separately as a native ES module.)
set -uo pipefail
export JAVA_HOME="/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"
clojure -M -m cljs.main --optimizations advanced \
  --output-dir /tmp/cljs-out --output-to ../resources/public/combobox-cljs.js \
  --compile spike-cljs.combobox
