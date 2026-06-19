#!/usr/bin/env bash
# Run the spike server, quittable with Ctrl-C.
#
# Uses the project's jabba-managed JDK 25 directly (jabba's shell hook isn't
# loaded in a non-interactive shell). The Clojure CLI's JVM doesn't reliably act
# on SIGINT (Ctrl-C), but it shuts down cleanly on SIGTERM — which runs the
# server's shutdown hook and frees the port. So we run the JVM as a child and
# translate Ctrl-C / TERM into a SIGTERM, then wait for it to actually exit.
set -uo pipefail
export JAVA_HOME="/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"

clojure -M -m spike.server "${1:-7777}" &
pid=$!
trap 'kill -TERM "$pid" 2>/dev/null' INT TERM
# wait is interrupted when the trap fires; loop until the JVM has really exited.
while kill -0 "$pid" 2>/dev/null; do wait "$pid"; done
