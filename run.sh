#!/usr/bin/env bash
# 启动科大课表（默认只监听本机 127.0.0.1:8765）
# 想用手机访问：HOST=0.0.0.0 ./run.sh，然后手机打开 http://电脑局域网IP:8765
set -euo pipefail
cd "$(dirname "$0")"

PY=${PYTHON:-python3}
HOST=${HOST:-127.0.0.1}
PORT=${PORT:-8765}

exec env HOST="$HOST" PORT="$PORT" "$PY" server.py
