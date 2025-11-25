#!/usr/bin/env bash
set -e

# Source jabba
export JABBA_HOME="$HOME/.jabba"
[ -s "$JABBA_HOME/jabba.sh" ] && source "$JABBA_HOME/jabba.sh"

# Use correct Java version
jabba use zulu@21.0.6

# Run tests
clojure -M:test -m kaocha.runner "$@"
