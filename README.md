# reservation-sim

A ticket reservation load simulator: a reservation server, a waiting-queue server, a mock payment gateway,
and a simulator with a live web UI that drives virtual users against them.

## Requirements

- Java 21
- Docker (PostgreSQL 17 for the reservation server, and Testcontainers for the tests)

## Run

Start the database:

```bash
docker compose up -d
```

Start each service in its own terminal, from the repository root:

```bash
./gradlew :mock-pg:bootRun     # mock payment gateway  http://localhost:8081
./gradlew :queue:bootRun       # waiting queue         http://localhost:8082
./gradlew :server:bootRun      # reservation server    http://localhost:8080
./gradlew :simulator:bootRun   # simulator + UI        http://localhost:8090
```

Open <http://localhost:8090/> and press **Start**.
Starting a run resets the reservation server, the queue and the mock payment gateway, so use dedicated instances.

## Test

```bash
./gradlew test
```

Docker must be running (the reservation server tests use Testcontainers).

## Benchmark environment (optional)

For load measurements and strategy comparisons, `scripts/bench-up.sh` builds the jars and starts all services
on separate ports (server 18180, mock-pg 18181, queue 18182, simulator 18190) with pinned CPUs, fixed heaps and a dedicated
PostgreSQL container. Run it from WSL/Linux at the repository root; stop everything with `scripts/bench-down.sh`.

```bash
scripts/bench-up.sh
# UI: http://localhost:18190/  (set the run targets to 18180 / 18182 / 18181)
scripts/bench-down.sh
```
