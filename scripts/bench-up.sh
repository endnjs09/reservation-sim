#!/usr/bin/env bash
# 측정 환경(bench)을 띄운다 — SPEC.md 10.4. WSL bash에서 레포 루트 기준으로 실행한다.
# 포트 배정은 docs/DECISION_CLAUDE.md에 기록되어 있다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DB_CONTAINER=reservation-bench-db
DB_NAME=reservation_bench
DB_PORT=55440
DB_CPUSET=6-11
DB_MEMORY=2g
SERVER_PORT=18180
PG_PORT=18181
QUEUE_PORT=18182
SIM_PORT=18190
SERVER_CPUS=0-5
SIM_CPUS=6-9
QUEUE_CPUS=10-11
PG_CPUS=10-11
SERVER_HEAP=(-Xms2g -Xmx2g)
SIM_HEAP=(-Xmx2g)
QUEUE_HEAP=(-Xmx512m)
PG_HEAP=(-Xmx256m)

LOGS="$ROOT/logs"
JARS="$ROOT/build/bench"
mkdir -p "$LOGS" "$JARS"

fail() { echo "bench-up: $*" >&2; exit 1; }
port_busy() { ss -ltnH "sport = :$1" | grep -q .; }

# 1. 포트가 비어 있어야 한다 (개발용 서버·이전 검증 환경과 섞지 않는다).
for port in $SERVER_PORT $PG_PORT $QUEUE_PORT $SIM_PORT; do
  port_busy "$port" && fail "포트 $port 사용 중. scripts/bench-down.sh 또는 해당 프로세스를 먼저 정리하세요."
done
if port_busy "$DB_PORT" && [ "$(docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null)" != "true" ]; then
  fail "포트 $DB_PORT를 다른 프로세스(예: reservation-w3-validation-db)가 쓰고 있습니다. 정리 후 다시 실행하세요."
fi

# 측정 잡음 경고: 같은 WSL에서 다른 컨테이너가 돌고 있으면 알린다 (끄지는 않는다).
others=$(docker ps --format '{{.Names}}' | grep -v "^${DB_CONTAINER}\$" || true)
[ -n "$others" ] && echo "bench-up: 경고 — 다른 컨테이너가 실행 중이라 측정에 잡음이 생길 수 있습니다: $(echo $others)"
echo "bench-up: 시작 전 스왑 사용량 $(free -m | awk '/^Swap:/{print $3}')MB"

# 2. 빌드: Gradle 데몬을 쓰지 않는다. 실행 중 jar가 바뀌지 않도록 build/bench로 복사한다.
./gradlew --stop >/dev/null
./gradlew --no-daemon -q :server:bootJar :queue:bootJar :mock-pg:bootJar :simulator:bootJar
for module in server queue mock-pg simulator; do
  jar=$(ls "$module"/build/libs/*.jar | grep -v -- '-plain\.jar$' | head -1)
  [ -n "$jar" ] || fail "$module jar 없음"
  cp "$jar" "$JARS/$module.jar"
done

# 3. 전용 Postgres (cpuset·메모리 고정).
if docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
  cpuset=$(docker inspect -f '{{.HostConfig.CpusetCpus}}' "$DB_CONTAINER")
  memory=$(docker inspect -f '{{.HostConfig.Memory}}' "$DB_CONTAINER")
  [ "$cpuset" = "$DB_CPUSET" ] && [ "$memory" = "$((2 * 1024 * 1024 * 1024))" ] \
    || fail "$DB_CONTAINER 제한값이 다릅니다 (cpuset=$cpuset memory=$memory). bench-down.sh --purge 후 다시 실행하세요."
  docker start "$DB_CONTAINER" >/dev/null
else
  docker run -d --name "$DB_CONTAINER" --cpuset-cpus "$DB_CPUSET" --memory "$DB_MEMORY" \
    -e POSTGRES_DB=$DB_NAME -e POSTGRES_USER=reservation -e POSTGRES_PASSWORD=reservation \
    -p 127.0.0.1:$DB_PORT:5432 postgres:17 >/dev/null
fi
for _ in $(seq 60); do
  docker exec "$DB_CONTAINER" pg_isready -U reservation -d $DB_NAME >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$DB_CONTAINER" pg_isready -U reservation -d $DB_NAME >/dev/null || fail "Postgres 준비 안 됨"


# 4. jar 기동 (서버 3개 먼저).
start() {
  local name=$1 cpus=$2; shift 2
  taskset -c "$cpus" nohup java "$@" > "$LOGS/$name.log" 2>&1 &
  echo $! > "$LOGS/$name.pid"
}
wait_for() {
  local name=$1 check=$2
  for _ in $(seq 120); do
    if eval "$check" >/dev/null 2>&1; then echo "bench-up: $name 준비됨"; return 0; fi
    kill -0 "$(cat "$LOGS/$name.pid")" 2>/dev/null || fail "$name 종료됨. logs/$name.log 확인"
    sleep 1
  done
  fail "$name 준비 시간 초과. logs/$name.log 확인"
}
SIM_ORIGIN="http://localhost:$SIM_PORT"
start mock-pg $PG_CPUS "${PG_HEAP[@]}" -jar "$JARS/mock-pg.jar" --server.port=$PG_PORT ${SERVERS_EXTRA_ARGS:-}
start queue $QUEUE_CPUS "${QUEUE_HEAP[@]}" -jar "$JARS/queue.jar" --server.port=$QUEUE_PORT --cors-origins=$SIM_ORIGIN ${SERVERS_EXTRA_ARGS:-}
start server $SERVER_CPUS "${SERVER_HEAP[@]}" -jar "$JARS/server.jar" --server.port=$SERVER_PORT \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:$DB_PORT/$DB_NAME \
  --reservation.pg.base-url=http://localhost:$PG_PORT --admission.queue-url=http://localhost:$QUEUE_PORT \
  --reservation.cors-origins=$SIM_ORIGIN ${SERVERS_EXTRA_ARGS:-}
wait_for mock-pg "curl -fs http://localhost:$PG_PORT/actuator/health"
wait_for queue "curl -fs http://localhost:$QUEUE_PORT/actuator/health"
wait_for server "curl -fs http://localhost:$SERVER_PORT/actuator/health"

# 5. bench 프로필 (run.json environment.bench로 기록된다). acceptCount는 설정값이 아니라 커널에 걸린 실제 대기 줄 크기.
backlog() { ss -ltnH "sport = :$1" | awk '{print $3}' | head -1; }
SOMAXCONN=$(cat /proc/sys/net/core/somaxconn)
cat > "$LOGS/bench.json" <<JSON
{
  "ports": { "db": $DB_PORT, "server": $SERVER_PORT, "mockPg": $PG_PORT, "queue": $QUEUE_PORT, "simulator": $SIM_PORT },
  "heap": { "server": "${SERVER_HEAP[*]}", "simulator": "${SIM_HEAP[*]}", "queue": "${QUEUE_HEAP[*]}", "mockPg": "${PG_HEAP[*]}" },
  "cpus": { "server": "$SERVER_CPUS", "simulator": "$SIM_CPUS", "queue": "$QUEUE_CPUS", "mockPg": "$PG_CPUS" },
  "db": { "cpuset": "$DB_CPUSET", "memory": "$DB_MEMORY" },
  "acceptCount": { "server": $(backlog $SERVER_PORT), "queue": $(backlog $QUEUE_PORT), "mockPg": $(backlog $PG_PORT), "somaxconn": $SOMAXCONN }
}
JSON
echo "bench-up: 연결 대기 줄 server=$(backlog $SERVER_PORT) queue=$(backlog $QUEUE_PORT) mockPg=$(backlog $PG_PORT) (somaxconn $SOMAXCONN)"

# 6. 시뮬레이터 (actuator가 없어 포트 열림으로 판단).
start simulator $SIM_CPUS "${SIM_HEAP[@]}" ${SIM_EXTRA_OPTS:-} -Dbench.profile="$LOGS/bench.json" -jar "$JARS/simulator.jar" --server.port=$SIM_PORT
wait_for simulator "port_busy $SIM_PORT"

echo "bench-up: 완료 — UI http://localhost:$SIM_PORT/ (실행 설정의 targets를 18180/18182/18181로)"
