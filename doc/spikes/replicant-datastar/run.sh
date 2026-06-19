#!/usr/bin/env bash
# Run the spike server. Uses the project's jabba-managed JDK 25 directly so it
# works in a non-interactive shell (jabba's shell hook isn't loaded here).
set -euo pipefail
export JAVA_HOME="/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"
exec clojure -M -m spike.server "${1:-7777}"
