#!/usr/bin/env bash
# 측정 환경(bench)을 정리한다 — SPEC.md 10.4.
# 기본: jar 프로세스 종료 + DB 컨테이너 정지 (데이터 유지). --purge: DB 컨테이너와 데이터 삭제.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"
DB_CONTAINER=reservation-bench-db

for name in simulator server queue mock-pg; do
  pidfile="$LOGS/$name.pid"
  [ -f "$pidfile" ] || continue
  pid=$(cat "$pidfile")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    for _ in $(seq 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
    echo "bench-down: $name 종료 (pid $pid)"
  fi
  rm -f "$pidfile"
done

if docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
  docker stop "$DB_CONTAINER" >/dev/null && echo "bench-down: $DB_CONTAINER 정지"
  if [ "${1:-}" = "--purge" ]; then
    docker rm -v "$DB_CONTAINER" >/dev/null && echo "bench-down: $DB_CONTAINER 삭제"
  fi
fi
