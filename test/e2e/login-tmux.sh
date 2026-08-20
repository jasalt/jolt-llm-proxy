#!/usr/bin/env bash
# Verify browser-login selection through a real detached tmux terminal.
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
session="jolt-login-tmux-$$"
auth_file="/tmp/$session.auth.json"

cleanup() {
  tmux has-session -t "$session" 2>/dev/null && tmux kill-session -t "$session" || true
  rm -f "$auth_file"
}
trap cleanup EXIT

pane() { tmux capture-pane -ep -t "$session":0.0; }
wait-for() {
  local needle=$1 timeout=${2:-120} screen
  for ((i = 0; i < timeout; i++)); do
    screen=$(pane)
    grep -Fq "$needle" <<<"$screen" && return 0
    sleep 1
  done
  printf 'Timed out waiting for %q in tmux pane:\n%s\n' "$needle" "$(pane)" >&2
  return 1
}

tmux new-session -d -s "$session" -x 120 -y 40 \
  "cd '$root' && HOME=/tmp JOLT_LLM_PROXY_AUTH_FILE='$auth_file' stty icanon -icrnl && jolt -m llm-proxy.core login"
wait-for '>'

# Reproduce the sandbox's literal CR under canonical input. The CLI must restore
# `icrnl` before this Enter event can complete the line.
tmux send-keys -t "$session":0.0 '1' C-m
wait-for 'Opening your browser for ChatGPT login...' 30
wait-for 'If it does not open, visit:' 5

printf 'tmux login browser selection passed\n'
