# reservation-sim 명세 (통합본)

> **이 파일 하나가 유일한 명세다.** `BASE_SPEC.md`(v0.3), `SPEC_v0.4.md`, `SPEC_v0.5.md`를 합치고 폐기된 규칙을 뺀 것이다.
> 예전 파일은 `docs/history/`에 참고용으로만 둔다. 예전 파일과 이 파일이 다르면 **이 파일이 맞다.**
>
> 변경 기록
> - 2026-10-04 통합본 1.1.2: W3 완료 기준을 bench 새 기준선 2개로 (18장, S4). W2 기준선 비교는 참고용. 확인 요청 기준은 `AGENTS.md`로 분리 (0장 3번).
> - 2026-10-04 통합본 1.1.1: 측정 환경(bench) 정의 10.4 추가, 사건 목록에 CLOCK_OFFSET_HIGH·SWAP_USED, 비교 경고에 ENV_DEGRADED 추가 (경고 8종).
> - 2026-10-04 통합본 1.1.0: **시계 기준점(anchor) 도입** (3장). 이 환경(WSL)의 벽시계가 실제보다 6~11% 빠르게 가고 단조 시계는 정확함을 확인(실제 120초 → uptime 120.00초, date 127초). 판단용 "현재 시각"을 벽시계 대신 `anchorAt + 단조 경과`로 계산. 관련: 6.14, 7.7, 8.5, 9.2(`CLOCK_MODEL_DIFFERS`), 10.2, 11.3, 12, 16.
> - 2026-10-04 통합본 1.0.3: 결정 기록을 에이전트별 파일로 (0장 2번).
> - 2026-10-04 통합본 1.0.2: 시계열 `t` 정의를 단조 시계 경과로 명확히 (8.5), earlyQuitRate 제거 방식(10.3, 9.1), `connections` 형태(11.9), 글꼴 Pretendard(13).
> - 2026-10-04 통합본 1.0.1: 2.3·2.4 패키지 구성을 예시로 명시 (코드 이동 불필요).
> - 2026-10-04 통합본 1.0: BASE + v0.4 + v0.5.2 통합. 통합하면서 새로 넣은 것: `contracts/` 계약 파일 규칙(0장), `saleEndFallback` 카운터(11.3), `environment.memAvailableMbAtStart`(8.5), tick `connections`(11.9), 목록의 `pinned`(11.9).

티켓 오픈 상황을 재현하는 **좌석 예약 서버 + 대기열 서버 + 모의 결제사 + 트래픽 시뮬레이터 + 시각화 UI**.
Spring Boot 4 (Java 21) / Postgres 17 / Docker Compose. 이 문서만 보고 구현할 수 있는 수준을 목표로 한다.

---

## 0. 작업 지침 (구현 에이전트용)

1. 18장의 단계 순서대로 구현한다. 단계마다 테스트를 통과시키고 멈춘 뒤 보고한다.
2. 명세에 없는 결정은 **에이전트별 결정 기록**에 남긴다: Codex는 `decision.md`, Claude Code는 `DECISION_CLAUDE.md`. 다른 에이전트의 파일은 읽기만 하고 고치지 않는다. 열: `결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유`.
3. **명세 파일은 수정하지 않는다.** 언제 멈추고 확인을 요청할지는 `AGENTS.md`의 기준을 따른다.
4. **테스트와 실제 응답이 다르면 먼저 이 명세를 확인하고, 명세와 다른 쪽을 고친다.** 테스트 기대값을 구현에 맞춰 바꾸지 않는다.
5. API 응답 형태는 실제 서버 테스트로 검증한다. 가짜 응답을 쓰는 테스트는 실제 서버 테스트와 같은 계약 파일(`contracts/*.json`)에서 만든다.
6. 이 통합본은 여러 문서를 합친 것이다. 합치는 과정에서 **현재 구현과 다른 점을 발견하면 고치기 전에 목록으로 보고한다.**
7. 17장 "구현하지 말 것"은 구현하지 않는다.
8. 불변식 검사기는 이 레포에 넣지 않는다. 이 레포는 검사에 필요한 스냅샷을 남기고(8.5), 외부 검사기가 쓴 결과를 보여주기만 한다(8.6). 구현한 쪽이 스스로 채점하지 않게 하기 위해서다.
9. API·요약 JSON은 **필드 추가만** 허용한다 (이 문서가 명시적으로 바꾸는 것은 예외).
10. git push 하지 않는다 (로컬 커밋만).

---

## 1. 목적과 원칙

- 실제 국내 티켓팅 흐름: **대기열 → 좌석 선점(다매·연석) → 결제(카드 2단계 / 입금 대기) → 확정**, 그리고 **취소표**.
- 서버 공부용 실험대: 같은 시나리오를 서버 전략만 바꿔 돌리고, 지연·에러·포화도를 측정·저장·비교한다.
- 정확성: 15장의 불변식을 만족해야 한다.
- 인증/인가, 실결제, 배포는 범위 밖. 사용자 식별은 `userId` 문자열.
- 모든 수치(좌석 수, 정원, TTL, 실패율 등)는 설정값이고 UI에서 바꿀 수 있다 (재시작이 필요한 커넥션 풀 크기 등은 제외).

---

## 2. 구조

### 2.1 모듈과 포트
```
reservation-sim/
├── settings.gradle          # include 'server', 'queue', 'mock-pg', 'simulator'
├── build.gradle             # 공통 (Java 21 toolchain)
├── docker-compose.yml       # postgres:17
├── SPEC.md                  # 이 문서
├── decision.md              # Codex 결정 기록
├── DECISION_CLAUDE.md       # Claude Code 결정 기록
├── contracts/               # API 응답 계약 파일 (0장 5번)
├── docs/
│   ├── ui-realtime.dc.html  # 실시간 화면 목업
│   ├── ui-results.dc.html   # 실행 결과 화면 목업
│   └── history/             # 예전 명세
├── server/      :8080  예약 서버       dev.endnjs.reservation
├── queue/       :8082  대기열 서버     dev.endnjs.queue
├── mock-pg/     :8081  모의 결제사     dev.endnjs.mockpg
└── simulator/   :8090  시뮬레이터 + UI dev.endnjs.simulator
```

| 모듈 | 의존성 |
|---|---|
| server | web, data-jpa, validation, actuator, postgresql, flyway(core, postgresql), HdrHistogram / 테스트: spring-boot-starter-test, testcontainers(postgresql, junit-jupiter) |
| queue | web, actuator, HdrHistogram. **DB 없음, 메모리 기반 단일 인스턴스** |
| mock-pg | web, actuator |
| simulator | web, HdrHistogram. **engine 패키지는 Spring 비의존 순수 Java** (CLI 재사용) |

- 실행: `docker compose up -d` → `:mock-pg:bootRun` → `:queue:bootRun` → `:server:bootRun` → `:simulator:bootRun` → 브라우저 `http://localhost:8090/`.
- DB 이름 `reservation`. 접속 사용자·비밀번호는 `docker-compose.yml`과 현재 `application.yml`의 값을 따른다.

### 2.2 요청 경로
```
사용자 ──① 진입·순번 폴링──▶ 대기열 서버          순번, 입장키 발급
   │◀──────── 입장키 ─────────┘
   │
   ├──② 입장키 + 요청────▶ 예약 서버 ──▶ DB         키 서명·만료만 검사 (대기열 서버 호출 없음)
   │                         ├──▶ 대기열 서버        자리 상태 알림 (선점 중 / 해제 / 완료)
   │                         └──▶ 결제사             승인 요청
   │
   └──③ 카드 인증─────────▶ 결제사                 사용자가 직접
```
- 대기자의 순번 폴링은 예약 서버에 **한 건도 닿지 않는다**.
- 입장 이후 요청은 사용자 → 예약 서버 직접. 예약 서버는 요청마다 대기열 서버를 부르지 않는다.

### 2.3 server 패키지 (예시)
> 아래는 역할 구분을 보여주는 **예시**다. 패키지 위치·클래스 이름은 현재 구현을 따르고, 이 목록에 맞추려고 코드를 옮기지 않는다. 실제 위치는 `DECISION_CLAUDE.md`(Claude Code) / `decision.md`(Codex)에 대응표로 남긴다.
```
dev.endnjs.reservation
├── config/      RuntimeConfig, ClockConfig
├── seat/        Seat, SeatController, Grade, SeatSummary
├── hold/        HoldService, HoldStrategy + 4개 구현, HoldController
├── payment/     CheckoutService, ConfirmService, PgClient, ConfirmRecoveryScheduler
├── deposit/     DepositService, DepositExpiryScheduler, ReopenScheduler
├── reservation/ ReservationController(조회·취소)
├── admission/   AdmissionKeyFilter, AdmissionKeyAuditor, SlotNotifier, RevokedKeys
├── sale/        SaleClock(saleEndAt, phase)
├── expiry/      ExpiryScheduler
├── metrics/     RequestMetricsFilter, MetricsCollector, MetricsStreamController
├── admin/       AdminController, StatsService, SnapshotService
└── common/      ApiError, ErrorCode, GlobalExceptionHandler
```

### 2.4 simulator 패키지 (예시, 2.3과 같은 원칙)
```
dev.endnjs.simulator
├── engine/   RunConfig, VirtualUser, Persona, Churn, SeatPicker, RunEngine, RunStats, Summary, ClientMetrics
├── cli/      SimulatorCli
├── service/  RunService, RunStore, CompareService, PresetStore, ServerMetricsPoller
├── api/      RunController, CompareController, PresetController, StreamController(SSE)
└── resources/static/   UI
```

---

## 3. 시간

- 모든 "현재 시각"은 주입된 `java.time.Clock`. SQL의 `now()` 금지 (파라미터로 전달).
- **시계 기준점 (anchor)**: 이 환경에서는 벽시계가 단조 시계보다 빠르게 간다. 그래서 판단에 쓰는 "현재 시각"을 벽시계에서 직접 읽지 않는다.
  - 시뮬레이터가 실행 시작 때 기준 시각 `anchorAt`(자기 벽시계 1회 읽기)과 자기 단조 시계 기준점을 **같은 순간에 한 번** 잡는다. `saleEndAt`, `startedAt`, 시계열 `t`는 모두 이 기준 하나에서 계산한다 (기준점을 두 개 두지 않는다).
  - `/admin/reset`으로 예약 서버·대기열 서버·mock-pg에 **같은 `anchorAt`**을 보낸다.
  - 각 서버의 Clock = `anchorAt + (System.nanoTime() − reset 수신 시 nanoTime)`. reset 전(기동 직후)에는 시스템 시계. 다시 reset하면 기준을 새로 잡는다.
  - 시뮬레이터 안의 시간 판단(판매 종료 보조 경로, 재방문 시각, 예매 취소 시점, `releaseAt` 비교 등)도 같은 anchor 시계를 쓴다. `Instant.now()`·시스템 시계 직접 호출 금지.
  - reset 수신 순서 때문에 서버끼리 수 ms 차이가 날 수 있다. 시뮬레이터는 reset 직후 각 서버 시각과 자기 시각의 차이를 재서 run.json `clockOffsetsMs`에 남기고, 500ms를 넘으면 `CLOCK_OFFSET_HIGH` 사건을 남긴다 (실행은 계속).
  - 지연·rps 측정은 원래대로 단조 시계로 한다 (영향 없음).
- **배속** `timeScale ∈ {1, 2, 4}`, 기본 4.
  - 설정의 모든 시간 길이 값은 **시뮬레이션 시간**으로 적는다. 실제 적용 = 설정값 ÷ timeScale.
  - 적용 대상: 선점 TTL, 입장 유효시간, 입금 기한, 반환 대기, 판매 시간, confirmDeadline, busy 상한, 시뮬레이터의 모든 사용자 행동 시간과 도착 분포.
  - 속도 값도 시뮬레이션 기준이다. `admitPerSec` 20은 4×에서 실제 초당 80명.
  - 적용하지 않는 것: 스케줄러 주기(실제 1초), 결제사 승인 지연(ms), 네트워크·요청 타임아웃.
  - **부하 측정·전략 비교는 1× 실행만 유효**하다 (9.2 경고, UI 안내).
- 계측과 tick에는 `simElapsedSec`, `timeScale`을 함께 보낸다.
- **판매 종료 시각** `saleEndAt = anchorAt + saleDurationSec ÷ timeScale`. 시뮬레이터가 한 번 계산해 예약 서버와 대기열 서버에 **같은 값**으로 보낸다 (10.2). 판매 종료는 시계열에서 `t = saleDurationSec ± (timeScale + 1)`초에 찍혀야 한다.

---

## 4. 도메인

### 4.1 좌석과 등급
- 행 `A`부터 `rows`개, 열 `1`부터 `cols`개. 기본 10 × 10 = 100석.
- `id = 행 인덱스 × cols + 열 인덱스 + 1`, `label = "A1"`.
- 등급은 앞줄부터 행 단위. 설정 `grades: [{name, rows, price}]` (행 수 합 = rows).

| 등급 | 행 수 | 기본 행 | 좌석 | 가격 |
|---|---|---|---|---|
| VIP | 1 | A | 10 | 150000 |
| S | 2 | B~C | 20 | 120000 |
| A | 3 | D~F | 30 | 90000 |
| B | 4 | G~J | 40 | 60000 |

### 4.2 상태값
| 대상 | 값 |
|---|---|
| seats.status | `AVAILABLE`, `HELD`, `PENDING_DEPOSIT`, `RETURN_PENDING`, `SOLD` (seatMap 코드 A/H/D/R/S) |
| reservations.status | `HELD`, `CONFIRMING`, `CONFIRMED`, `EXPIRED`, `RELEASED`, `PAYMENT_FAILED`, `PENDING_DEPOSIT`, `DEPOSIT_EXPIRED`, `CANCELED` |
| payments.status | `REQUESTED`, `APPROVED`, `FAILED`, `CANCELED` |
| 대기 토큰 (대기열 서버 메모리) | `WAITING`, `ADMITTED`, `COMPLETED`, `LEFT`, `EXPIRED`, `CLOSED` |

- 좌석 HELD는 예약 HELD 또는 CONFIRMING 동안 유지된다.
- 한 예약은 좌석 1~4개. 한 예약의 좌석은 **항상 같은 상태**다 (부분 선점·부분 반환 없음).

### 4.3 예약 상태 전이
| 현재 | 사건 | 조건 | 다음 | 좌석·부수 효과 |
|---|---|---|---|---|
| (없음) | 선점 성공 | 전 좌석 확보 | HELD | 좌석 → HELD |
| HELD | 선점 취소 (`release`) | - | RELEASED | 좌석 즉시 반환 |
| HELD | 만료 스케줄러 | now ≥ hold_expires_at | EXPIRED | 좌석 즉시 반환 |
| HELD | 승인 요청 (`confirm`) | 6.6 확인 통과 | CONFIRMING | `confirm_deadline = now + confirmDeadlineSec` |
| HELD | 승인 요청 | 만료됨 | (유지) | 409 RESERVATION_NOT_PAYABLE, 결제사 호출 없음 |
| HELD | 입금 대기 선택 (`/deposit`) | 만료 전 | PENDING_DEPOSIT | 좌석 → PENDING_DEPOSIT, 대기열 자리 COMPLETED 알림 |
| CONFIRMING | 결제사 승인 성공 (또는 복구에서 DONE) | - | CONFIRMED | 결제 APPROVED, 좌석 → SOLD, COMPLETED 알림 |
| CONFIRMING | 승인 거절 / 복구에서 취소 확정 | - | PAYMENT_FAILED | 결제 FAILED/CANCELED, 좌석 즉시 반환 |
| PENDING_DEPOSIT | 입금 | 기한 전, 금액 일치 | CONFIRMED | 좌석 → SOLD |
| PENDING_DEPOSIT | 입금 마감 스케줄러 | 기한 경과 | DEPOSIT_EXPIRED | 좌석 → RETURN_PENDING (취소표 배치 편입) |
| PENDING_DEPOSIT | 사용자 취소 | - | CANCELED | 좌석 즉시 반환 |
| CONFIRMED | 사용자 취소 | 카드면 결제사 취소 성공 | CANCELED | 좌석 즉시 반환 |
| (좌석) RETURN_PENDING | 취소표 오픈 | 배치 시각 도달 | - | 좌석 → AVAILABLE (배치 일괄) |
| 그 외 | 모든 사건 | - | 유지 | 로그만 |

- **CONFIRMING은 만료 스케줄러가 건드리지 않는다.**
- **좌석 반환은 항상 조건부**: `WHERE id IN (...) AND current_reservation_id = :res`. 예약이 끝나면 `reservation_seats.released_at`을 채운다.

### 4.4 좌석이 돌아오는 경로
| 경로 | 방식 |
|---|---|
| 선점 만료, 선점 취소, 카드 결제 실패(거절·망취소) | 즉시 |
| 사용자 예매 취소 (CONFIRMED, PENDING_DEPOSIT) | 즉시 |
| 입금 대기 미입금 | 반환 대기 → **배치로 일괄** (6.8) |

### 4.5 정책
| 항목 | 정책 |
|---|---|
| 선점 TTL | `holdTtlSec` 기본 420 |
| 1인 선점 | 사용자당 HELD/CONFIRMING 예약 최대 1건 |
| 1인 1예매 | CONFIRMED 또는 PENDING_DEPOSIT 예약이 있으면 선점 불가 (409 USER_ALREADY_PURCHASED). 한 예매 최대 `maxSeatsPerUser`(4)매 |
| 결제 주문 | 예약당 결제 1건. checkout 재요청 시 기존 주문 반환 |
| 승인 직전 확인 | 예약 HELD, 만료 전, 사용자·금액 일치일 때만 결제사 승인 호출 |
| 승인 결과 불명 | 상태 조회 1회 → 그래도 불명이면 CONFIRMING 유지, 복구 스케줄러 |
| 선점 재시도 | `Idempotency-Key` 멱등. 같은 키 + 같은 내용 → 같은 응답, 다른 내용 → 422 |

---

## 5. 데이터베이스 (server, Flyway)

### 5.1 테이블 (V1 + V2 + V3 결과)
```sql
seats (id BIGINT PK, label VARCHAR(10) UNIQUE, row_index INT, col_index INT, grade VARCHAR(16), price INT,
       status VARCHAR(16) CHECK (status IN ('AVAILABLE','HELD','PENDING_DEPOSIT','RETURN_PENDING','SOLD')),
       current_reservation_id BIGINT, release_batch_id BIGINT, version BIGINT DEFAULT 0, updated_at TIMESTAMPTZ)

reservations (id BIGSERIAL PK, seat_id BIGINT REFERENCES seats(id),   -- 대표 좌석(가장 작은 id)
       user_id VARCHAR(64), status VARCHAR(20) CHECK (... 4.2의 9개 ...),
       seat_count INT DEFAULT 1, payment_method VARCHAR(16),          -- CARD | DEPOSIT
       hold_expires_at TIMESTAMPTZ, confirm_deadline TIMESTAMPTZ, deposit_deadline TIMESTAMPTZ,
       idempotency_key VARCHAR(64), admission_kid UUID,
       created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)
  UNIQUE (idempotency_key), INDEX (status, hold_expires_at), INDEX (user_id)

reservation_seats (reservation_id, seat_id, released_at TIMESTAMPTZ, PK (reservation_id, seat_id))
  INDEX (seat_id)

payments (id UUID PK /* = orderId */, reservation_id BIGINT UNIQUE, amount INT,
       status VARCHAR(16) CHECK (status IN ('REQUESTED','APPROVED','FAILED','CANCELED')),
       payment_key VARCHAR(64), fail_reason VARCHAR(64), created_at, updated_at)

release_batches (id BIGSERIAL PK, release_at TIMESTAMPTZ, released BOOLEAN DEFAULT FALSE, seat_count INT DEFAULT 0)
```
- 마이그레이션: `V1__init.sql`, `V2__v04.sql`(다매·입금·배치), `V3__drop_queue_tokens.sql`(`DROP TABLE IF EXISTS queue_tokens;` + `reservations.admission_kid`. W3에서 이미 다른 이름으로 만들었으면 그 이름을 유지하고 보고).
- **좌석 목록의 기준은 `reservation_seats`**다. `reservations.seat_id`는 대표 좌석일 뿐이다.

### 5.2 최후 방어선 인덱스 (backstop)
애플리케이션 시작 시와 `/admin/reset` 시 `dbBackstop` 값에 따라 만들거나 지운다 (Flyway 아님).
```sql
-- dbBackstop = true
CREATE UNIQUE INDEX IF NOT EXISTS ux_rs_seat_active ON reservation_seats(seat_id) WHERE released_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_res_user_active ON reservations(user_id) WHERE status IN ('HELD','CONFIRMING');
-- dbBackstop = false
DROP INDEX IF EXISTS ux_rs_seat_active;
DROP INDEX IF EXISTS ux_res_user_active;
```
- 위반은 각각 409 `SEAT_UNAVAILABLE`, 409 `USER_ALREADY_HOLDING`으로 변환.
- `naive` + backstop off에서만 초과 판매가 관측되어야 한다 (보정용).

### 5.3 커넥션 풀
- Hikari `maximum-pool-size` 기본 20 (yml, 재시작 필요). 실험 변수.

---

## 6. 예약 서버 (:8080)

### 6.1 설정 (`application.yml`)
```yaml
server.port: 8080
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/reservation   # 사용자·비밀번호는 docker-compose.yml 값
    hikari.maximum-pool-size: 20
  jpa: { hibernate.ddl-auto: validate, open-in-view: false }
reservation:
  strategy: conditional          # conditional | pessimistic | optimistic | naive
  db-backstop: true
  time-scale: 4
  hold-ttl-sec: 420
  max-seats-per-user: 4
  deposit-deadline-sec: 60
  return-delay-sec: 30
  sale-duration-sec: 1200
  reopen-window-sec: 50
  seats: { rows: 10, cols: 10 }
  grades: [ {name: VIP, rows: 1, price: 150000}, {name: S, rows: 2, price: 120000},
            {name: A, rows: 3, price: 90000},    {name: B, rows: 4, price: 60000} ]
  payment.confirm-deadline-sec: 30
  scheduler: { expiry-interval-ms: 1000, recovery-interval-ms: 5000, deposit-interval-ms: 1000, reopen-interval-ms: 1000, expiry-batch-size: 500 }
  pg: { base-url: http://localhost:8081, connect-timeout-ms: 1000, request-timeout-ms: 3000 }
admission:
  required: true                 # false면 입장키 검사 생략 (단위 테스트용)
  key-secret: dev-only-change-me # 대기열 서버와 같은 값
queue-server.base-url: http://localhost:8082   # SlotNotifier 대상
internal.secret: dev-internal-secret            # 대기열 서버와 같은 키·같은 값
cors-origins: [ "http://localhost:8090" ]
```
- `strategy`, `db-backstop`, 시간 값, 좌석·등급, 결제 값은 **RuntimeConfig**로 들고 `/admin/reset`으로 바꾼다.

### 6.2 공통
- JSON, UTF-8. 시각은 ISO-8601 UTC.
- 에러 응답 `{ "code", "message" }`.

| code | HTTP | 의미 |
|---|---|---|
| VALIDATION_FAILED | 400 | 요청 형식 오류 |
| KEY_INVALID | 403 | 입장키 형식·서명·runEpoch·userId 불일치 |
| KEY_EXPIRED | 403 | 입장키 만료 (6.3 예외 제외) |
| KEY_REVOKED | 403 | 구매를 끝낸 키 |
| SEAT_NOT_FOUND | 404 | 없는 좌석 |
| RESERVATION_NOT_FOUND | 404 | 없는 예약 |
| SEAT_UNAVAILABLE | 409 | 이미 선점/판매된 좌석 (`unavailableSeatIds` 포함) |
| USER_ALREADY_HOLDING | 409 | 이미 HELD/CONFIRMING 예약 있음 |
| USER_ALREADY_PURCHASED | 409 | 이미 예매함 (CONFIRMED/PENDING_DEPOSIT) |
| RESERVATION_NOT_PAYABLE | 409 | HELD 아님, 만료, 다른 사용자, 금액 불일치 |
| DEPOSIT_NOT_ACCEPTABLE | 409 | 입금 불가 (상태·기한·금액) |
| RESERVATION_NOT_CANCELABLE | 409 | 취소 불가 상태 |
| SALE_ENDED | 409 | 판매 종료 (6.10) |
| PAYMENT_DECLINED | 402 | 결제사 승인 거절 |
| IDEMPOTENCY_KEY_REUSED | 422 | 같은 키 다른 내용 |
| PG_UNAVAILABLE | 502 | 결제사 호출 실패 (checkout·취소) |

### 6.3 입장키 검사 (`AdmissionKeyFilter`)
- 대상: `GET /seats`, `/holds*`, `/payments/confirm`. (`/deposits/*/pay`, `/reservations/*/cancel`, `GET /reservations/{id}`는 검사 없음)
- 헤더 `X-Admission-Key` (형식 7.3).

| 검사 | 실패 시 |
|---|---|
| 형식·서명 | 403 KEY_INVALID |
| `run` = 현재 runEpoch | 403 KEY_INVALID |
| `uid` = 요청 본문 userId (`/seats`는 생략) | 403 KEY_INVALID |
| `kid`가 회수 목록에 없음 | 403 KEY_REVOKED |
| `now < exp` | 아래 예외가 아니면 403 KEY_EXPIRED |

- **만료 예외 (결제 도중 보호)**: 키가 만료돼도 그 사용자에게 HELD/CONFIRMING 예약이 있으면 그 예약의 `/holds/{id}/checkout`, `/holds/{id}/deposit`, `/holds/{id}/release`, `/payments/confirm`은 통과. `/seats`, 새 `/holds`는 막는다.
- **회수 목록** (`RevokedKeys`, 메모리, reset 시 비움): COMPLETED 알림을 보낸 kid.
- 대기열 서버를 호출하지 않는다.
- **독립 재검증**: `RequestMetricsFilter`(가장 바깥)가 보호 대상 요청이 2xx로 끝나면 별도 구현 `AdmissionKeyAuditor`(서명·runEpoch·uid, 만료 예외 동일 규칙)로 다시 확인. 실패면 `counters.acceptedInvalidKeys` +1 (불변식 I17).
- `admission.required = false`면 검사 생략.

### 6.4 좌석 조회 `GET /seats`
```json
{ "rows": 10, "cols": 10,
  "grades": [ { "name": "VIP", "rows": 1, "price": 150000 } ],
  "seats": [ { "id": 1, "label": "A1", "grade": "VIP", "status": "AVAILABLE" } ],
  "availableSeats": 0, "heldSeats": 10, "pendingDepositSeats": 4, "returnPendingSeats": 2,
  "soldOut": false, "releaseAt": "...|null", "saleEndAt": "...", "phase": "RESALE" }
```
- 요약 필드 8개(`availableSeats` ~ `phase`)는 **반드시** 있다. 계약 파일 `contracts/seats-response.json`과 실제 서버 테스트로 검증한다.
- `soldOut` = AVAILABLE 0 이고 HELD 0 (PENDING_DEPOSIT·RETURN_PENDING·SOLD만 남음).
- 트랜잭션 없이 읽어도 된다 (약간 오래된 값 허용).
- 판매 종료 후에는 409 SALE_ENDED (6.10).

### 6.5 선점
**`POST /holds`** 헤더 `Idempotency-Key`(1~64자), `X-Admission-Key`
```json
{ "userId": "u-0001", "seatIds": [37, 38] }
```
- `seatIds` 1~maxSeatsPerUser개, 중복 금지. 단일 `seatId` 필드도 받는다 (1개로 취급).
- **전부 아니면 전무**: 하나라도 못 잡으면 아무것도 잡지 않고 409 SEAT_UNAVAILABLE + `unavailableSeatIds`.
- **잠금 순서**: 항상 seat id 오름차순 (교착 방지).
- 처리 순서 (한 트랜잭션)
  1. 키 검사 (필터), 판매 종료 확인 (6.10).
  2. 멱등: `idempotency_key`로 예약 조회. 있으면 같은 내용일 때 원래와 같은 201 본문, 다르면 422.
  3. (naive 제외) 사용자 직렬화 `SELECT pg_advisory_xact_lock(hashtext(:userId))`.
  4. 사용자 확인: 예매 있음 → 409 USER_ALREADY_PURCHASED, HELD/CONFIRMING 있음 → 409 USER_ALREADY_HOLDING.
  5. 좌석 확보 (전략별 아래 표). 실패하면 못 잡은 좌석마다 충돌 카운터 +1.
  6. 예약 INSERT (HELD, `hold_expires_at = now + holdTtl`, `admission_kid`), `reservation_seats` 행, 좌석 `current_reservation_id`.
  7. 커밋 후 201, 그리고 HOLD_ACTIVE 알림 (6.11).
- 같은 키 동시 요청으로 유니크 충돌 시: 롤백 → 새 트랜잭션에서 2번부터.
- 응답 201: `{ "reservationId", "seats": [{ "id", "label", "grade", "price" }], "totalPrice", "status": "HELD", "holdExpiresAt" }`

| 전략 | 방법 |
|---|---|
| conditional | `UPDATE seats SET status='HELD', ... WHERE id = ANY(:ids) AND status='AVAILABLE'`. 영향 행 수 ≠ n이면 롤백 |
| pessimistic | `SELECT ... WHERE id = ANY(:ids) ORDER BY id FOR UPDATE` → 전부 AVAILABLE일 때만 UPDATE |
| optimistic | 좌석마다 version 비교 UPDATE. 하나라도 실패하면 롤백. 재시도 없음 |
| naive | 락 없이 확인 후 조건 없는 UPDATE. advisory lock도 생략 (보정용) |

**`POST /holds/{id}/release`** `{ "userId" }` → HELD이고 사용자 일치면 RELEASED + 좌석 반환. 이미 HELD 아니면 200으로 현재 상태 (멱등).

### 6.6 카드 결제 (국내식 2단계)
```
사용자 ──① checkout──▶ 서버            주문(orderId, 금액) 생성
사용자 ──② 카드 인증──▶ 결제사          paymentKey 발급 (출금 없음)
사용자 ──③ confirm───▶ 서버 ──④ 확인 후 승인──▶ 결제사   이때 출금
```
**① `POST /holds/{id}/checkout`** `{ "userId" }` → 예약 FOR UPDATE, 사용자 불일치·HELD 아님·만료 → 409 RESERVATION_NOT_PAYABLE. 결제가 있으면 그대로, 없으면 INSERT (REQUESTED, 금액 = 좌석 가격 합). 200 `{ "orderId", "amount" }`. 결제사 호출 없음.

**② 카드 인증**: 사용자가 mock-pg `POST /auth` 직접 호출 (12장).

**③ `POST /payments/confirm`** `{ "userId", "orderId", "paymentKey", "amount" }`
1. (트랜잭션 A) 결제 → 예약 FOR UPDATE. 사용자 불일치, HELD 아님, 만료, 금액 불일치, 결제 REQUESTED 아님 → 409 RESERVATION_NOT_PAYABLE (**결제사 승인 호출 없음**). 통과: 예약 CONFIRMING, `confirm_deadline`, `payment_key` 저장. 커밋.
2. (트랜잭션 밖) mock-pg `POST /payments/confirm`.
3. (트랜잭션 B) 결과 반영
   - DONE → 결제 APPROVED, 예약 CONFIRMED, 좌석 SOLD(조건부), COMPLETED 알림 → **200** `{ "status": "CONFIRMED" }`
   - 4xx 거절 → 결제 FAILED, 예약 PAYMENT_FAILED, 좌석 반환 → **402**
   - 타임아웃·5xx·연결 실패 → mock-pg `GET /payments/{paymentKey}` 1회
     - DONE → 성공 처리 → 200
     - CANCELED / 404 → 결제 CANCELED(404면 FAILED), 예약 PAYMENT_FAILED, 좌석 반환 → 402
     - AUTHORIZED / DECLINED → 망취소 `POST /payments/{key}/cancel`: 성공 → 위와 같이 402, 실패 → CONFIRMING 유지 → **202**
     - 조회 실패 → CONFIRMING 유지 → **202** `{ "status": "CONFIRMING" }` (사용자는 `GET /reservations/{id}` 폴링)

**복구 스케줄러** (`recovery-interval-ms`): `CONFIRMING AND confirm_deadline <= :now`. 예약마다 **항상 상태 조회부터** 다시 한다.
| 조회 결과 | 처리 |
|---|---|
| DONE | 성공 처리 (+ COMPLETED 알림, kid 회수) |
| CANCELED / 404 | PAYMENT_FAILED, 좌석 반환 (+ HOLD_CLEARED 알림) |
| AUTHORIZED / DECLINED | 망취소 → 성공하면 위와 같음, 실패하면 CONFIRMING 유지 |
| 조회 실패 | CONFIRMING 유지, 다음 주기 |
- 재시도 횟수 제한 없음. 시도마다 `counters.recoveryAttempts` +1, 최종 처리 시 `counters.recovered` +1.

### 6.7 입금 대기·입금
**`POST /holds/{id}/deposit`** `{ "userId" }`
- HELD이고 만료 전이어야 함 (아니면 409 RESERVATION_NOT_PAYABLE).
- 예약 PENDING_DEPOSIT, `payment_method = DEPOSIT`, `deposit_deadline = now + depositDeadlineSec`. 좌석 PENDING_DEPOSIT. COMPLETED 알림 (자리 반납).
- 200 `{ "status": "PENDING_DEPOSIT", "depositDeadline", "amount" }`. 가상계좌는 흉내만 (결제사 호출 없음).

**`POST /deposits/{reservationId}/pay`** `{ "userId", "amount" }` (시뮬레이터가 은행 역할, 키 검사 없음)
- PENDING_DEPOSIT, 기한 전, 금액 일치 → CONFIRMED, 좌석 SOLD. 그 외 409 DEPOSIT_NOT_ACCEPTABLE.

### 6.8 입금 마감 · 취소표 배치
- **입금 마감 스케줄러** (1초): `PENDING_DEPOSIT AND deposit_deadline <= :now` → DEPOSIT_EXPIRED, 좌석 RETURN_PENDING.
  - 열린 배치(`released = false`)가 없으면 `release_at = now + returnDelaySec`인 배치를 새로 만들고, 있으면 합류 (첫 미입금 기준 카운트다운).
  - 좌석 `release_batch_id` 설정, 배치 `seat_count` 증가.
- **취소표 오픈 스케줄러** (1초): `release_at <= :now`인 열린 배치마다 한 트랜잭션에서 RETURN_PENDING 좌석 전부 AVAILABLE, `release_batch_id` NULL, 배치 `released = true`. 이벤트 `REOPEN`(좌석 수), `lastReopenAt` 갱신.

### 6.9 예매 취소·조회
**`POST /reservations/{id}/cancel`** `{ "userId" }` (키 검사 없음)
- CONFIRMED + CARD: mock-pg cancel → 성공하면 결제 CANCELED, 예약 CANCELED, 좌석 즉시 AVAILABLE / 실패하면 502, 상태 그대로.
- CONFIRMED + DEPOSIT, PENDING_DEPOSIT: 예약 CANCELED, 좌석 즉시 AVAILABLE.
- 그 외: 409 RESERVATION_NOT_CANCELABLE.

**`GET /reservations/{id}`** → `{ "reservationId", "seatId", "label", "grade", "seats": [...], "userId", "status", "holdExpiresAt", "paymentMethod", "depositDeadline", "payment": { "orderId", "status" } | null }` (`seatId`·`label`·`grade`는 대표 좌석)

### 6.10 판매 종료
- `now ≥ saleEndAt`이면
  - `GET /seats`, 새 `POST /holds` → 409 SALE_ENDED (판매 종료 스케줄러가 돌기 전이라도).
  - HELD 예약 → EXPIRED, 좌석 반환 (스케줄러).
  - CONFIRMING·PENDING_DEPOSIT은 각자 규칙대로 결론까지 진행 (복구·입금 마감 계속).
  - 열린 취소표 배치는 오픈 이벤트 없이 좌석을 AVAILABLE로만 되돌린다.
  - 이미 있는 예약의 checkout·deposit·release·confirm·입금·취소·조회는 계속 처리.
- 토큰 종료는 대기열 서버가 한다 (7.6).

### 6.11 대기열 서버로 알림 (`SlotNotifier`)
- 예약 생성·종료 지점에서 보낸다. 요청 처리 트랜잭션 **커밋 후** 큐에 넣고 비동기 전송.

| 사건 | 보내는 이벤트 |
|---|---|
| 선점 성공 | HOLD_ACTIVE |
| 그 사용자의 HELD/CONFIRMING이 모두 끝남 (만료·해제·결제 실패, 요청 밖 포함) | HOLD_CLEARED |
| 카드 CONFIRMED (요청 안이든 복구 스케줄러든), 입금 대기 신청 | COMPLETED + kid를 회수 목록에 추가 |
| soldOut 값이 바뀜, 그리고 5초마다 | `/internal/sale-state` |
- 이벤트의 `at`은 사건이 일어난 시각 (재시도 시각 아님).
- 실패 시 재시도 3회 (실제 1초/2초/4초). 그래도 실패하면 버리고 `counters.slotNotifyDropped` +1, `droppedNotifications`에 `{ kid, type, at }` 기록 (스냅샷 포함).

### 6.12 phase (계측·UI용)
| 값 | 조건 (위부터 먼저 맞는 것) |
|---|---|
| ENDED | 판매 종료 이후 |
| OPEN | 선점 0건 |
| RUSH | AVAILABLE이 한 번도 0이 된 적 없음 |
| REOPEN | 마지막 취소표 오픈 후 `reopenWindowSec`(50, 시뮬레이션) 이내이고 AVAILABLE > 0 |
| SOLD_OUT | soldOut = true |
| RESALE | 그 외 (취켓팅) |

### 6.13 만료 스케줄러 (`expiry-interval-ms`)
- `HELD AND hold_expires_at <= :now`인 예약을 `ORDER BY hold_expires_at LIMIT :batch FOR UPDATE SKIP LOCKED`로 잡아 EXPIRED, 그 예약의 `reservation_seats` 좌석을 조건부 반환하고 `released_at` 기록, HOLD_CLEARED 알림.
- CONFIRMING 제외. 처리 건수 = batch면 같은 주기에 반복.

### 6.14 관리 API (실험 계약)
**`POST /admin/reset`** (트래픽 없을 때만)
```json
{ "rows": 10, "cols": 10, "grades": [ ... ],
  "strategy": "conditional", "dbBackstop": true, "timeScale": 4,
  "holdTtlSec": 420, "confirmDeadlineSec": 30, "maxSeatsPerUser": 4,
  "depositDeadlineSec": 60, "returnDelaySec": 30, "saleDurationSec": 1200, "reopenWindowSec": 50,
  "anchorAt": "...", "saleEndAt": "...", "runEpoch": "...", "closeQueueOnSoldOut": false }
```
- `closeQueueOnSoldOut`은 받기만 하고 쓰지 않는다 (시뮬레이터가 두 서버에 같은 본문 구성을 보내기 위함).
- 모든 필드 선택, 생략 시 현재 값 유지. grades 합 ≠ rows → 400.
- `anchorAt`이 오면 3장대로 Clock 기준을 새로 잡는다. `saleEndAt`이 오면 그 값을 쓰고, 없으면 `now + saleDurationSec ÷ timeScale` (now는 anchor 시계).
- 테이블 TRUNCATE → 좌석 재생성 → backstop 적용 → 캐시·카운터·회수 목록·알림 큐 초기화 → 200 `{ "config" }`.

**`GET /admin/stats`**
```json
{ "serverTime": "...", "config": { ... },
  "seats": { "total": 100, "AVAILABLE": 12, "HELD": 30, "PENDING_DEPOSIT": 4, "RETURN_PENDING": 0, "SOLD": 54, "byGrade": { ... } },
  "reservations": { "HELD": 0, "CONFIRMING": 0, "CONFIRMED": 0, "EXPIRED": 0, "RELEASED": 0, "PAYMENT_FAILED": 0, "PENDING_DEPOSIT": 0, "DEPOSIT_EXPIRED": 0, "CANCELED": 0 },
  "payments": { "REQUESTED": 0, "APPROVED": 0, "FAILED": 0, "CANCELED": 0 },
  "releaseBatches": { "open": 1, "nextReleaseAt": "...|null", "totalReleased": 3 },
  "counters": { "holdAttempts": 0, "holdSuccess": 0, "holdConflicts": 0, "confirms": 0, "declines": 0, "notPayable": 0,
                "expiredByScheduler": 0, "recoveryAttempts": 0, "recovered": 0,
                "immediateReturns": 0, "userCancels": 0, "depositsRequested": 0, "depositsPaid": 0, "depositsExpired": 0,
                "reopenCount": 0, "reopenSeats": 0, "slotNotifyDropped": 0, "acceptedInvalidKeys": 0 } }
```
- 상태별 수는 DB GROUP BY, counters는 메모리 LongAdder (reset 시 0).

**`GET /admin/metrics`** — 8.4의 계측 스냅샷. **`GET /admin/metrics/stream`** — 1초마다 SSE `metrics`.
**`GET /admin/snapshot`** — seats, reservations, reservation_seats, payments, release_batches 전체 행 + counters + `droppedNotifications` (불변식 검사용).

---

## 7. 대기열 서버 (:8082)

### 7.1 상태 (메모리, 재시작하면 사라짐)
| 항목 | 내용 |
|---|---|
| 대기 토큰 | `{ tokenId, userId, seq, status, createdAt, admittedAt, endedAt, endReason }`. 끝난 토큰도 reset 전까지 보관 |
| 입장 자리(slot) | ADMITTED 토큰마다 `{ keyId, userId, admittedAt, expiresAt, busy, busySince }` |
| 사용자 인덱스 | userId → 활성 토큰 (WAITING/ADMITTED는 사용자당 1개) |
| 입장 기록 | 주기마다 `{ tickAt, admitted, seqFrom, seqTo, activeAfter }`. 최댓값 `activeMax`, `admittedPerTickMax` 유지 |
| 판매 상태 | `saleEndAt`(reset), `soldOut`(예약 서버 알림) |

### 7.2 입장 규칙
- 입장 스케줄러 1초: 입장 수 = `min(maxActive − 현재 자리 수, admitPerSec × interval)`, seq 오름차순. 입장 시 입장키 발급, `expiresAt = now + admissionTtlSec`.
- 순번은 매 주기 계산해 두고 요청 때 읽기만 한다.
- 자리 반납: COMPLETED(예약 서버 알림), LEFT(`/queue/leave`), EXPIRED(`now ≥ expiresAt`이고 busy 아님).
- **만료 미룸**: busy인 자리는 만료하지 않는다 (결제 도중 보호). 결제 중 TTL을 넘긴 busy는 **정상**이다.
- **busy 상한**: busy여도 `now ≥ expiresAt + busyMaxExtraSec`이면 강제 만료. 기본 510 (= holdTtl 420 + confirmDeadline 30 + 60, 시뮬레이션 초). 강제 만료마다 `expiredByBusyCap` +1, `busyCapExpirations`에 `{ kid, userId, at }`.
- EXPIRED·LEFT 사용자가 다시 진입하면 맨 뒤 새 토큰.
- **이탈은 입장 후에만** 일어난다 (11.4). 대기열 서버가 대기자를 임의로 내보내는 경우는 7.6뿐이다.

### 7.3 입장키
- 형식 `v1.<payload>.<sig>` (base64url, 패딩 없음)
  - payload `{ "kid": "<UUID>", "uid": "<userId>", "iat": <ms>, "exp": <ms>, "run": "<runEpoch>" }`
  - sig `HMAC-SHA256(keySecret, "v1." + payload)`, 비교는 `MessageDigest.isEqual`.
- `keySecret`은 예약 서버와 공유 (yml). `runEpoch`는 실행마다 새 값 (두 서버에 같은 값).

### 7.4 사용자 API
**`POST /queue/enter`** `{ "userId" }` → 활성 토큰이 있으면 그대로, 없으면 새 WAITING (또는 7.6의 CLOSED). 200 `{ "token", "status", "position", "pollAfterMs", "reason" }`

**`GET /queue/status?token=`**
- WAITING `{ "status", "position", "pollAfterMs" }`
- ADMITTED `{ "status", "admissionKey", "admissionExpiresAt" }`
- 그 외 `{ "status", "reason": "SALE_ENDED" | "SOLD_OUT" | null }`
- 없는 토큰 400. `pollAfterMs`: position ≤ 50 → 1000, ≤ 300 → 2000, ≤ 1000 → 4000, 그 외 6000.
- **좌석 상황 필드는 없다.** 대기 중인 사람은 실제 서비스처럼 좌석 상황을 모른다.

**`POST /queue/leave`** `{ "token" }` → WAITING/ADMITTED면 LEFT, 자리 반납.

### 7.5 내부 API (예약 서버 → 대기열 서버)
헤더 `X-Internal-Secret` 필수, 틀리면 401.

**`POST /internal/slots/{kid}/events`** `{ "type": "HOLD_ACTIVE" | "HOLD_CLEARED" | "COMPLETED", "at" }`
| type | 처리 |
|---|---|
| HOLD_ACTIVE | busy = true |
| HOLD_CLEARED | busy = false (이미 만료 시각이 지났으면 다음 주기 EXPIRED) |
| COMPLETED | 토큰 COMPLETED, 자리 반납 |
- 없는 kid·끝난 자리 → 200 `{ "ignored": true }`.
- kid마다 마지막 반영 `at`보다 오래된 이벤트는 무시. COMPLETED 이후 모든 이벤트 무시.

**`POST /internal/sale-state`** `{ "soldOut", "releaseAt" }` → 7.6에만 사용.

| 알림 유실 | 영향 | 회수 |
|---|---|---|
| HOLD_ACTIVE | 결제 중 자리가 만료될 수 있음 (예약 서버 만료 예외로 결제는 계속) | 없음 (정원이 잠깐 1 늘어남) |
| HOLD_CLEARED | 자리가 busy로 묶임 | busy 상한 |
| COMPLETED | 끝난 사용자가 자리 차지 | 만료 시각 (+ busy 상한) |
| sale-state | closeQueueOnSoldOut 반영 지연 | 5초 주기 재전송 |

### 7.6 판매 상태 처리
- **판매 종료** (`saleEndAt`): WAITING/ADMITTED 전부 CLOSED(`SALE_ENDED`). 이후 enter는 200 + CLOSED(`SALE_ENDED`) 토큰.
- **`closeQueueOnSoldOut`** (기본 false): true이고 soldOut이면 WAITING 전부와 busy 아닌 ADMITTED를 CLOSED(`SOLD_OUT`), 이후 enter도 CLOSED(`SOLD_OUT`). soldOut=false가 오면 다시 접수. false면 아무도 내보내지 않는다.

### 7.7 관리 API
- `POST /admin/reset` `{ "anchorAt", "maxActive", "admitPerSec", "admissionTtlSec", "busyMaxExtraSec", "timeScale", "saleEndAt", "closeQueueOnSoldOut", "runEpoch" }` (`anchorAt`으로 3장의 Clock 기준을 잡는다)
- `GET /admin/stats` → `{ "WAITING", "ADMITTED", "COMPLETED", "LEFT", "EXPIRED", "CLOSED", "busy", "slotsBusyOverTtl", "counters": { "entered", "admitted", "keysIssued", "expired", "expiredByBusyCap", "closedSoldOut", "closedSaleEnded", "slotEvents": { "HOLD_ACTIVE", "HOLD_CLEARED", "COMPLETED", "ignored" } } }`
- `GET /admin/metrics` → 8.4
- `GET /admin/snapshot` → 토큰 전체, 현재 자리, 입장 기록, `activeMax`, `admittedPerTickMax`, `busyCapExpirations`

### 7.8 설정
```yaml
server.port: 8082
queue: { max-active: 200, admit-per-sec: 20, admission-ttl-sec: 420, busy-max-extra-sec: 510,
         close-queue-on-sold-out: false, admission-interval-ms: 1000 }
admission.key-secret: dev-only-change-me
internal.secret: dev-internal-secret
cors-origins: [ "http://localhost:8090" ]
```

---

## 8. 계측과 실행 기록

### 8.1 서버 지표 4가지 (화면·저장·비교 공통)
| 신호 | 정의 | 임계치 기본 (warn / bad) |
|---|---|---|
| 처리량 | 예약 서버 사용자 엔드포인트 초당 요청 합 (`/admin/**` 제외) | - |
| 응답 시간 | 예약 서버 사용자 엔드포인트 합친 1초 창 p50/p95/p99 (ms). 대표 p95 | p95 100 / 300 (SLO) |
| 에러율 | 1초 창 non-2xx ÷ 전체 × 100 | 8% / 25% |
| 포화도 | DB 풀 `active ÷ max × 100`. 보조: 풀 대기, 락 대기, 스레드 사용률 | 70% / 90% |
- 임계치는 UI 고급 설정에서 바꾸고 실행 기록에 저장한다.
- 4신호는 시뮬레이터가 **한 곳에서 계산**(`signals`, `levels`)해 실시간 화면과 저장 시계열에 같은 값을 쓴다.
- **실패율** `failPct` = 클라이언트 측 (예약 서버 5xx + timeout + transport) ÷ 시뮬레이터가 예약 서버로 보낸 요청 × 100. 에러율과 따로 본다 (409는 경쟁의 결과지 고장이 아니다).

### 8.2 응답 시간 측정
- **서버 측** (`RequestMetricsFilter`): 진입 ~ 응답 커밋. 엔드포인트별 HdrHistogram(1µs~60s, 유효숫자 3), 1초 창은 `Recorder`로 교체.
  - 예약 서버 키: `seats`, `holds`, `release`, `checkout`, `confirm`, `reservation`, `deposit`, `depositPay`, `cancel`.
  - 대기열 서버 키: `queue.enter`, `queue.status`, `queue.leave`, `internal.events`.
  - 예약 서버 `PgClient`: 승인·조회·취소 호출별.
- **누적**: reset 이후 엔드포인트별 누적 히스토그램도 유지 (`cumulative`). 요약의 백분위는 이 값을 쓴다 (초별 백분위는 합칠 수 없다).
- **클라이언트 측** (시뮬레이터): 전송 직전 ~ 본문 수신. 엔드포인트별 1초 창 + 누적. 대기열·결제사 요청 포함.

### 8.3 에러 분류
| 분류 | 포함 |
|---|---|
| conflict | 409 SEAT_UNAVAILABLE, USER_ALREADY_HOLDING, USER_ALREADY_PURCHASED |
| notPayable | 409 RESERVATION_NOT_PAYABLE, DEPOSIT_NOT_ACCEPTABLE, RESERVATION_NOT_CANCELABLE, SALE_ENDED |
| key | 403 KEY_INVALID, KEY_EXPIRED, KEY_REVOKED |
| declined | 402 |
| client | 그 외 4xx |
| shed | 429, 503 (현재 발생하지 않음, 과부하 대응 실험용 자리) |
| server | 5xx |
| timeout | (클라이언트만) 응답 시간 초과, 기본 10초 실제 |
| transport | (클라이언트만) 연결 실패·끊김 |

### 8.4 `/admin/metrics`
**예약 서버**
```json
{ "at": "...", "windowMs": 1000, "simElapsedSec": 41, "timeScale": 1, "phase": "RUSH",
  "endpoints": { "holds": { "rps": 140, "inflight": 22, "avgMs": 12.5,
                 "latency": { "p50": 8.1, "p95": 41.0, "p99": 88.0, "max": 130.2 },
                 "status": { "2xx": 18, "409": 122 }, "errors": { "SEAT_UNAVAILABLE": 120 } } },
  "total": { "rps": 410, "latency": { ... }, "errorClasses": { "conflict": 122, "notPayable": 1, "key": 3, "declined": 0, "client": 0, "shed": 0, "server": 0 } },
  "cumulative": { "holds": { "count": 0, "p50": 0, "p95": 0, "p99": 0, "max": 0 }, "total": { ... } },
  "inflightTotal": 34,
  "pool": { "active": 18, "idle": 2, "pending": 3, "max": 20, "acquireMs": { "p95": 12.0, "max": 40.0 }, "timeouts": 0 },
  "db": { "lockWaits": 7, "lockWaitMaxMs": 180 },
  "http": { "threadsBusy": 64, "threadsMax": 200 },
  "jvm": { "heapUsedMb": 412, "heapMaxMb": 2048, "gcPauseMs": 3, "liveThreads": 230 },
  "pg": { "confirmInflight": 3, "confirmAvgMs": 240, "confirm": { "p50": 240, "p95": 470, "p99": 520 }, "timeouts": 0 },
  "notifier": { "queued": 0, "sent": 120, "retried": 2, "dropped": 0 },
  "schedulers": { "expiry": { "lastRunAt", "expired" }, "recovery": { "lastRunAt", "recovered" }, "depositExpiry": { "lastRunAt", "expired" }, "reopen": { "lastRunAt", "reopened" } },
  "seatMap": "SSSHHAD...", "heldRemainingMs": { "12": 154000 }, "depositRemainingMs": { "40": 30000 },
  "seatConflicts": { "3": 6 }, "releaseAt": "...|null", "returnPendingSeats": 2, "saleEndAt": "...",
  "events": { "REOPEN": 0, "USER_CANCEL": 0, "DEPOSIT_EXPIRED": 0 },
  "metricsSelfMs": 0.8 }
```
| 항목 | 얻는 방법 |
|---|---|
| pool | Hikari `HikariPoolMXBean` + `MetricsTrackerFactory`(획득 시간·타임아웃) |
| db.lockWaits / lockWaitMaxMs | 1초마다 `SELECT count(*), coalesce(max(extract(epoch from (:now - query_start)) * 1000), 0) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'` |
| http | 내장 Tomcat `ThreadPoolExecutor` (가상 스레드면 threadsMax null) |
| jvm | MemoryMXBean, GarbageCollectorMXBean 1초 차분, ThreadMXBean |
| seatConflicts | 최근 2초 좌석별 409 SEAT_UNAVAILABLE 수 |
| events | 최근 1초 사건 수 |
- 계측 쿼리 실패는 무시하고 이전 값 유지.
- CORS: `cors-origins`에 대해 `/admin/**` GET 허용 (대기열 서버·mock-pg도 같음).

**대기열 서버**: `{ "windowMs", "endpoints": { "queue.status": { "rps", "latency", "status" } }, "cumulative", "waiting", "active", "busy", "slotsBusyOverTtl", "expiredByBusyCap", "admittedThisTick", "keysIssued", "jvm" }`

**mock-pg** `/admin/stats`에 `confirmLatency { p50, p95, p99 }` 추가.

### 8.5 실행 기록 (`simulator/runs/{runId}/`)
```
run.json            메타·설정·환경
summary.json        요약 (11.8)
timeseries.ndjson   1초 1줄
events.ndjson       사건
snapshot.json       종료 후 상태 덤프 (불변식 검사용)
invariants.json     외부 검사기가 씀 (없을 수 있음)
```
- runId = `run-` + 8자리 hex. 쓰기는 임시 파일 → rename. ndjson은 1초마다 append + flush.
- 보관 `runs.keep` 50, 넘으면 오래된 것부터 삭제. `pinned: true`는 제외.
- 예전 형식(`./runs/*.json` 단일 파일, schemaVersion < 5)은 읽기만, "구버전 · 요약만". 읽을 수 없는 파일은 목록에서 빼고 `warnings`에 파일명.

**run.json**
```json
{ "schemaVersion": 5, "runId": "run-7f3a12c0", "label": "조건부 UPDATE · 기본", "notes": "", "pinned": false,
  "status": "COMPLETED", "statusReason": null, "startedAt": "...", "endedAt": "...",
  "durationMs": 1202000, "simDurationSec": 1200, "timeScale": 1,
  "config": { "...": "RunConfig 전체" },
  "server": { "strategy": "conditional", "dbBackstop": true, "poolMax": 20, "threadsMax": 200, "virtualThreads": false },
  "queueMode": "EXTERNAL",
  "thresholds": { "p95WarnMs": 100, "p95SloMs": 300, "errWarnPct": 8, "errBadPct": 25, "poolWarnPct": 70, "poolBadPct": 90 },
  "anchorAt": "...", "clockOffsetsMs": { "server": 3, "queue": 5, "mockPg": 2 },
  "environment": { "cpuCores": 12, "memAvailableMbAtStart": 8200, "javaVersion": "21.0.4", "os": "Linux (WSL2)", "gitCommit": "a1b2c3d|null" },
  "fingerprint": "sha256:…" }
```
- status: RUNNING / COMPLETED / STOPPED / FAILED (`statusReason`).
- `queueMode`: 대기열 분리 전 실행은 `EMBEDDED`, 이후 `EXTERNAL`. W3에서 내장 대기열을 지운 뒤에는 EMBEDDED 실행이 불가능하고, 기존 EMBEDDED 기록은 읽기·비교만 한다. EMBEDDED 기록에서 대기열 서버 전용 값(`busy`, `slotsBusyOverTtl`, `expiredByBusyCap`, `admittedThisTick`, snapshot의 `queue`)은 null이며 I8″·I16은 검사 대상 아님.
- `server.*`는 시작 시 서버 config·metrics에서 실제 값을 읽어 기록.

**timeseries.ndjson** (1줄 3KB 이하)
```json
{"t":41,"wallMs":41000,"phase":"RUSH",
 "signals":{"rps":410,"p50":6.0,"p95":38.0,"p99":95.0,"errPct":30.2,"failPct":0.0,"poolPct":90,"poolPending":3,"lockWaits":7,"threadsBusyPct":32},
 "server":{"endpoints":{"holds":{"rps":140,"p50":8.1,"p95":41.0,"p99":88.0,"err":{"conflict":122}}},
           "errorClasses":{...},"pool":{"active":18,"pending":3,"acquireP95":12.0},"db":{"lockWaits":7,"lockWaitMaxMs":180},
           "http":{"threadsBusy":64},"jvm":{"heapUsedMb":412,"gcPauseMs":3},"pg":{"confirmP95":470,"timeouts":0},"notifier":{"dropped":0}},
 "queue":{"waiting":812,"active":200,"busy":41,"slotsBusyOverTtl":3,"expiredByBusyCap":0,"admittedThisTick":20,"statusRps":540,"statusP95":1.2},
 "client":{"rps":980,"p95":{"holds":44.0},"timeout":0,"transport":0},
 "mockPg":{"authInflight":30,"confirmInflight":3},
 "users":{"arriving":300,"waiting":812,"admitted_browsing":140,"holding":22,"authenticating":30,"confirming":4,"done":692},
 "seats":{"A":12,"H":30,"D":4,"R":0,"S":54}}
```
- `t` = 시뮬레이션 경과 초 (그래프 가로축) = `floor(실행 시작부터의 단조 시계(nanoTime) 경과 × timeScale)`. **수집 횟수로 세지 않는다.** 수집은 고정 주기(`scheduleAtFixedRate`, 실제 1초)로 하고, 수집이 밀리면 t가 건너뛸 수 있다 (줄이 빠진 초는 그래프에서 이전 값과 잇지 않는다).
- `t`와 `saleEndAt`은 같은 anchor에서 나온다 (3장). 판매 종료는 `t = saleDurationSec ± (timeScale + 1)`초 (1×면 ±2초).
- `wallMs` = 실제 경과 ms. 요청 0인 창의 백분위는 null.

**events.ndjson**: `PHASE`, `SOLD_OUT`, `REOPEN`(seats, revisits), `SALE_ENDED`, `SLO_BREACH_START/END`, `POOL_SAT_START/END`, `NOTIFY_DROPPED`, `BUSY_CAP_EXPIRED`, `CLOCK_OFFSET_HIGH`(3장), `SWAP_USED`(10.4)만. 사용자 단위 사건은 저장하지 않는다.

**snapshot.json**: 종료 후 정지 대기(TTL + confirmDeadline + 5초, 시뮬레이션) 뒤 `{ "takenAt", "quiesced", "server": /admin/snapshot, "queue": /admin/snapshot, "mockPg": /admin/payments }`. 중지한 실행은 즉시, `quiesced: false`.

### 8.6 `invariants.json` (외부 검사기가 씀, 시뮬레이터는 읽기만)
```json
{ "checker": "harness/invariant-checker", "checkerVersion": "0.1.0", "checkedAt": "...",
  "results": [ { "id": "I1", "pass": true, "detail": "", "evidence": null } ] }
```

---

## 9. 실행 비교

### 9.1 fingerprint
- RunConfig에서 아래를 뺀 나머지를 키 정렬 JSON → SHA-256.
- **실험 변수** (configDiff에 나오고 개수를 셈): `strategy`, `dbBackstop`, `poolMax`, `threadsMax`, `virtualThreads`, `queueMode`.
- **제외** (fingerprint·변수 개수 모두 제외): `label`, `notes`, `thresholds`, `targets.*`, `requestTimeoutMs`, `busyMaxExtraSec`, `earlyQuitRate`(폐기, 예전 기록 호환).

### 9.2 `GET /api/compare?a=&b=`
```json
{ "a": "...", "b": "...", "comparable": true, "sameScenario": true,
  "configDiff": [ { "key": "server.strategy", "a": "conditional", "b": "pessimistic" } ],
  "warnings": [], "metrics": [ { "key", "label", "unit", "a", "b", "better", "winner", "diffPct" } ], "verdict": "..." }
```
| 경고 | 조건 |
|---|---|
| SCENARIO_DIFFERS | fingerprint 다름 |
| MULTIPLE_VARIABLES | 실험 변수 2개 이상 다름 |
| TIME_SCALED | timeScale ≠ 1인 실행 포함 |
| NOT_COMPLETED | COMPLETED 아님 |
| OLD_SCHEMA | schemaVersion < 5 (comparable = false, 요약만) |
| ENV_DIFFERS | cpuCores 또는 os 다름, 또는 `environment.bench`(10.4)가 다름 |
| ENV_DEGRADED | 둘 중 하나라도 `SWAP_USED` 사건이 있음 (측정 중 메모리 부족) |
| CLOCK_MODEL_DIFFERS | 한쪽 run.json에만 `clockOffsetsMs`가 있음 (anchor 도입 전 실행은 실제 판매 시간이 6~11% 짧다). 문구: "누적 지표는 판매 시간이 달라 참고만, 폭주 구간 지표(p95Rush, errPctRush, poolSatSec)로 비교하세요" |

### 9.3 비교 지표 (순서 고정)
p95Max(낮음), p99Max(낮음), p95Rush(낮음), sloBreachSec(낮음), errPctRush(낮음), failPct(낮음), conflict(낮음), poolPctMax(낮음), poolSatSec(낮음), lockWaitsMax(낮음), rpsMax(표시만), soldOutAtSec(표시만), seatsSold(표시만), invariants(높음).
- `diffPct = (b − a) ÷ a × 100` (a = 0이면 null). 같으면 winner null.
- **판정 문장** (규칙): ① p95Max의 winner → ② 진 쪽의 가장 큰 차이 보조 지표 하나를 이유로 → ③ 경고가 있으면 "(주의: …)".

### 9.4 시계열 정렬
- 가로축 `t`로 겹친다. 단계 음영은 A 기준, B의 단계 경계는 가로축 아래 작은 눈금.
- 다운샘플 `step=N`: N초 평균, 백분위는 N초 중 최대. 폭 600px 기준 N = ceil(길이 ÷ 300).

---

## 10. 시뮬레이터 실행

### 10.1 형태
- 서비스 모드 (`:8090`, UI). 한 번에 실행 1개.
- CLI 모드 `./gradlew :simulator:runCli --args="--config run.json --out result.json"` (엔진만, 같은 요약 JSON). 종료 코드 0 정상, 2 incomplete.

### 10.2 실행 시작
1. 대기열 서버·예약 서버·결제사 `GET /actuator/health`. 하나라도 실패 → 실행을 만들지 않고 409 `TARGET_DOWN { "target" }`.
2. `anchorAt`·단조 기준점을 같은 순간에 잡고(3장), `runEpoch` 생성, `saleEndAt` 계산 → 세 서버 `/admin/reset` (+ mock-pg config). 세 서버에 같은 anchorAt, 대기열·예약 서버에 같은 runEpoch·saleEndAt. 하나라도 실패 → status FAILED.
   - reset 직후 각 서버 시각과의 차이를 재서 `clockOffsetsMs`에 기록 (3장).
3. run.json 작성 (서버 실제 설정, 가용 메모리 포함).
4. 사용자 시작.
- 실행 중 계측 수집이 연속 5초 실패 → FAILED(`METRICS_LOST`), 사용자 정지.

### 10.3 RunConfig (UI 입력과 1:1)
| 그룹 | 항목 | 기본값 | 적용 대상 |
|---|---|---|---|
| 기본 | label, notes | 전략명 · 시각 / "" | 기록 |
| 좌석 | rows × cols, grades | 10 × 10, VIP 1/S 2/A 3/B 4행 | server |
| 사용자 | users | 2000 | sim |
| 사용자 | arrival | 60%: 0~5초, 30%: 5~60초, 10%: 60~300초 | sim |
| 사용자 | personaMix (fast/normal/slow) | 20/70/10 % | sim |
| 사용자 | churnMix (casual/persistent/hardcore) | 70/20/10 % | sim |
| 사용자 | casualLeaveSec | 0~60 | sim |
| 사용자 | persistentHalfLifeSec | 180 | sim |
| 사용자 | revisitProb (c/p/h) | 0.1/0.5/0.9 | sim |
| 사용자 | ticketCountMix (1/2/3/4) | 30/55/10/5 % | sim |
| 사용자 | adjacentRequiredRate | 0.9 | sim |
| 사용자 | seed | 42 | sim |

- `earlyQuitRate`는 폐기 (설정·프리셋·UI에서 제거). 단, 사용자 생성 때 그 자리에서 뽑던 난수 1회는 **그대로 뽑아 버린다** — 같은 seed에서 예전 실행과 사용자 행동이 같게 유지되도록.

| 대기열 | maxActive / admitPerSec / admissionTtlSec | 200 / 20 / 420 | queue |
| 대기열 | closeQueueOnSoldOut | false | queue |
| 대기열 | busyMaxExtraSec | 510 | queue |
| 예약·결제 | holdTtlSec / maxSeatsPerUser | 420 / 4 | server |
| 예약·결제 | paymentMix (card/deposit) | 85/15 % | sim |
| 예약·결제 | depositDeadlineSec / depositNoPayRate | 60 / 0.4 | server / sim |
| 예약·결제 | returnDelaySec / reopenWindowSec | 30 / 50 | server |
| 예약·결제 | abandonRate (선점 후 결제 포기) | 4% | sim |
| 예약·결제 | cancelAfterPurchaseRate | 0.02 | sim |
| 예약·결제 | authFailureRate / declineRate / timeoutRate | 3% / 3% / 0% | mock-pg |
| 예약·결제 | confirmMinMs ~ confirmMaxMs | 100 ~ 500 | mock-pg |
| 판매 | saleDurationSec | 1200 | server, queue |
| 서버 | strategy / dbBackstop | conditional / true | server |
| 실행 | timeScale | 4 | 전부 |
| 계측 | thresholds | 8.1 기본값 | 기록·UI |
| 실행 | timeLimitSec | 현재 구현 값 유지 (BASE 기본 600은 판매 시간 1200보다 짧다. 구현 값이 판매 시간 + 정지 대기보다 짧으면 보고) | sim |
| 실행 | queueMode | EXTERNAL (W3 완료 전에는 EMBEDDED 가능) | sim |
| 접속 | targets (server, queue, pg), requestTimeoutMs | :8080, :8082, :8081, 10000 | sim |

**페르소나별 시간** (구간 내 균등)
| | fast | normal | slow |
|---|---|---|---|
| 좌석 고르기 | 0.2~0.8초 | 1~3초 | 3~8초 |
| 가격·수령 단계 | 2~5초 | 5~15초 | 15~40초 |
| 카드 인증 | 5~15초 | 10~40초 | 60~240초 |
| 새로고침 간격 | 1초 | 2초 | 3초 |

- 사용자 i의 난수는 `new SplittableRandom(seed + i)`. 행동만 재현, 네트워크 타이밍은 재현 대상 아님.


### 10.4 측정 환경 (bench)
부하 측정·전략 비교용 실행은 아래 고정 환경에서 한다. 개발용으로 띄운 서버(기본 포트)와 섞지 않는다.

| 항목 | 값 |
|---|---|
| 포트 | 현재 검증 환경 그대로: DB 55440, 서버들 18180~18190 대역 (구체 배정은 스크립트에 고정하고 `DECISION_CLAUDE.md`에 기록) |
| DB | 전용 Postgres 컨테이너 (`cpuset` 6-11, 메모리 2g) |
| 실행 방식 | `bootJar`로 만든 jar를 `java -jar`로 실행. `bootRun`·Gradle 데몬 사용 안 함 (`./gradlew --stop` 후) |
| 예약 서버 | `taskset -c 0-5`, `-Xms2g -Xmx2g` |
| 시뮬레이터 | `taskset -c 6-9`, `-Xmx2g` |
| 대기열 서버 | `taskset -c 10-11`, `-Xmx512m` |
| mock-pg | `taskset -c 10-11`, `-Xmx256m` |
| WSL | `.wslconfig` memory 20GB, swap 4GB, processors 12 (사용자가 설정) |

- 스크립트: `scripts/bench-up.sh`(빌드·기동·health 대기), `scripts/bench-down.sh`(정리). 로그는 `logs/`.
- run.json `environment.bench`에 위 값(포트, 힙, CPU 배치)을 기록한다. 값이 다른 두 실행을 비교하면 `ENV_DIFFERS`.
- 실행 중 스왑 사용이 100MB를 넘으면 `SWAP_USED` 사건을 남긴다 (`ENV_DEGRADED` 경고 근거).
- 비교용 기준선은 이 환경에서 뜬 실행만 쓴다. bench 도입 전 실행(W2 EMBEDDED 기준선 포함)은 참고용 비교만 한다.

---

## 11. 가상 사용자

### 11.1 속성
관람객 속도(fast/normal/slow), 이탈 성향(casual/persistent/hardcore), 매수(1~4), 연석 고집, 결제 수단(card/deposit), 입금 여부(deposit일 때), 결제 포기, 예매 후 취소(+ 시점).

### 11.2 흐름
```
도착 → 대기열 진입 → 순번 폴링 → 입장(키) → 좌석 조회 → 선점 → 결제 → 확정/입금 대기 → 퇴장
```
1. 도착 시각까지 대기 → 대기열 서버 `POST /queue/enter`.
2. `pollAfterMs` 간격으로 `GET /queue/status`.
   - ADMITTED → `admissionKey` 저장 → 3.
   - CLOSED(SOLD_OUT) → 퇴장 (결과 soldOut), 재방문 대상 (11.5).
   - CLOSED(SALE_ENDED) → 퇴장.
3. 입장 후 반복 (예약 서버 요청에 `X-Admission-Key`)
   1. `GET /seats` → 좌석 선택(11.6) → 좌석 고르는 시간 → `POST /holds` (시도마다 새 Idempotency-Key)
      - 409 SEAT_UNAVAILABLE → 즉시 3.1
      - 빈 좌석 없음 → 새로고침 간격 후 3.1 (이탈 성향 11.4 적용)
   2. 선점 성공 → 결제 포기 사용자는 그대로 떠남 (TTL 만료)
   3. 가격·수령 단계 체류
   4. 결제
      - card: checkout → 카드 인증 시간 → mock-pg `/auth` (AUTH_FAILED → release, 3.1) → `/payments/confirm`
        - 200 → 확정, 퇴장 / 402 → 3.1 / 409 NOT_PAYABLE → 3.1 / 202 → `GET /reservations/{id}` 1초 폴링
      - deposit: `/holds/{id}/deposit` → 퇴장. 입금할 사용자는 `U(0.1, 0.9) × depositDeadlineSec` 시점에 `/deposits/{id}/pay`
   5. 예매 후 취소 사용자: 확정 후 `U(0, 판매 종료까지 남은 시간 × 0.8)` 시점에 `/reservations/{id}/cancel`
4. 403 KEY_EXPIRED / KEY_REVOKED / KEY_INVALID → `requeues++`, 1로 (맨 뒤 재진입).
5. 떠날 때 `POST /queue/leave`.
6. 실행 제한 시간(`timeLimitSec`)에 도달했을 때 아직 결론이 없는 사용자는 결과 `incomplete`.

### 11.3 판매 종료 인식
- **기본**: 예약 서버의 409 SALE_ENDED 또는 `/seats`의 `phase = ENDED` → 대기열에서 나가고 결과 확정.
- **보조**: (anchor 시계 기준으로) `saleEndAt`이 지났는데 `/seats`에서 정상 응답을 받지 못할 때(transport·timeout·5xx)만 공통 `saleEndAt`으로 종료. 이 경우 `events.saleEndFallback` +1.

### 11.4 이탈 성향 — 입장 후 "잔여석 없음 상태"에서만
"잔여석 없음 상태" = 입장 후 받은 좌석 맵의 AVAILABLE이 0. **대기 중에는 적용하지 않는다** (좌석 상황을 모르므로).
| 유형 | 행동 |
|---|---|
| casual | 처음 잔여석 없음을 본 시점부터 `U(casualLeaveMin, casualLeaveMax)` 뒤 떠남. 그 전에 좌석이 생기면 정상 진행 |
| persistent | 새로고침마다 이탈 확률 `1 − 0.5^(Δt / persistentHalfLifeSec)` (t = 잔여석 없음이 이어진 시간) |
| hardcore | 떠나지 않음. 키 만료 시 즉시 재진입. 판매 종료까지 |
- 좌석이 생기면 잔여석 없음 시간은 0으로 초기화.

### 11.5 재방문
- 구매 못 하고 떠난 사용자는 취소표 재방문 대기 상태.
- 시뮬레이터가 1초마다 예약 서버 `/admin/stats`의 `releaseBatches.nextReleaseAt`을 읽어(내부 판단용, 사용자 요청으로 집계 안 함), 오픈 1회당 사용자별 1번 `revisitProb`로 그 시각 ± U(0, 5)초에 다시 도착.

### 11.6 좌석 선택 (n매)
1. AVAILABLE 중 가장 앞줄. 80%는 그 줄부터, 20%는 다음 줄부터.
2. n = 1: 가운데 우선 가중치 `cols/2 + 0.5 − |col − (cols−1)/2|`.
3. n ≥ 2 + 연석 고집: 줄 안 연속 빈 좌석 n개 구간 (여러 개면 가운데 가중치), 없으면 다음 줄, 끝까지 없으면 "좌석 없음".
4. n ≥ 2 + 연석 아님: 연석 먼저, 없으면 가까운 줄의 빈 좌석 n개.

### 11.7 측정 정의
- sent: 시도한 요청 수 (엔드포인트별). latency: 8.2 클라이언트 측. responses: 상태 코드 클래스별, 응답 못 받으면 timeout/transport.
- 사용자 상태 분포: arriving, waiting, admitted_browsing, holding, authenticating, confirming, done.

### 11.8 요약 JSON (`summary.json`, 실험 계약 — 추가만 허용)
```json
{ "startedAt", "durationMs", "config": { ... },
  "requests": { "sent": 0, "byEndpoint": { ... } },
  "responses": { "2xx": 0, "4xx": 0, "5xx": 0, "transportErrors": 0 },
  "avgLatencyMs": { ... },
  "outcomes": { "confirmed", "soldOut", "gaveUp", "abandoned", "incomplete", "error", "depositPaid", "depositExpired", "canceledAfterPurchase", "revisited" },
  "outcomesByPersona": { ... }, "outcomesByChurn": { "casual": { ... }, "persistent": { ... }, "hardcore": { ... } },
  "events": { "conflicts", "refreshes", "requeues", "authFailed", "declined", "holdExpired", "immediateReturnsSeen", "reopenSeen", "saleEndFallback" },
  "seatsSold": 100, "seatsByGrade": { ... },
  "signals": { "rpsMax", "p95Max", "p99Max", "p95Rush", "errPctRush", "failPct", "poolPctMax", "poolSatSec", "lockWaitsMax", "sloBreachSec", "soldOutAtSec" },
  "latencyMs": { "holds": { "p50", "p95", "p99" } }, "clientLatencyMs": { ... },
  "errorClasses": { "conflict", "notPayable", "key", "declined", "client", "shed", "server", "timeout", "transport" } }
```
| 필드 | 정의 |
|---|---|
| p95Rush, errPctRush | phase = RUSH인 초들의 평균 |
| poolSatSec | poolPct ≥ poolBadPct인 초 수 |
| sloBreachSec | p95 ≥ p95SloMs인 초 수 |
| soldOutAtSec | AVAILABLE이 처음 0이 된 t |
| latencyMs | 서버 `cumulative` 종료 시점 값 |

### 11.9 시뮬레이터 API (:8090)
| 메서드 / 경로 | 설명 |
|---|---|
| `POST /api/runs` | RunConfig로 시작 (10.2). 실행 중이면 409, 대상 다운 409 TARGET_DOWN |
| `POST /api/runs/current/stop` | 중지 → STOPPED, 스냅샷 즉시 |
| `GET /api/runs/current` | 현재 상태 + 실시간 집계 |
| `GET /api/runs` | 목록 `[{ runId, label, status, startedAt, durationMs, timeScale, strategy, queueMode, schemaVersion, hasInvariants, invariantsPassed, fingerprint, pinned }]` + `warnings` |
| `GET /api/runs/{id}` | run.json + summary.json |
| `PATCH /api/runs/{id}` | `{ label, notes, pinned }` |
| `DELETE /api/runs/{id}` | 삭제 |
| `GET /api/runs/{id}/timeseries?fields=&step=` | 시계열 |
| `GET /api/runs/{id}/events` | 사건 |
| `GET /api/runs/{id}/invariants` | invariants.json 또는 404 |
| `GET /api/compare?a=&b=` | 9.2 |
| `GET/PUT /api/presets/{name}`, `GET /api/presets` | 프리셋 (`./presets/*.json`) |
| `GET /api/stream` | SSE 1초마다 `tick` |

**tick**
```json
{ "runId", "elapsedMs", "running": true, "simElapsedSec", "timeScale",
  "users": { ... }, "outcomes": { ... }, "events": { ... }, "clientRps": { ... },
  "pg": { "authInflight", "confirmInflight", "failed" },
  "recentEvents": [ "00:41.2 u-0412 D5·D6 선점 성공" ],
  "signals": { "rps", "p50", "p95", "p99", "errPct", "failPct", "poolPct", "poolPending", "lockWaits", "threadsBusyPct",
               "levels": { "p95": "ok|warn|bad", "err": "...", "pool": "..." } },
  "server": { ...예약 서버 metrics }, "queueServer": { ...대기열 서버 metrics }, "queueStats": { ...대기열 stats },
  "mockPg": { ...mock-pg stats },
  "connections": { "server": { "ok": true, "lastOkAt": "...", "error": null }, "queue": { ... }, "mockPg": { ... } } }
```
- `ServerMetricsPoller`가 1초마다 세 서버를 가져와 tick과 timeseries 한 줄을 같은 값으로 만든다.
- `recentEvents`: 최근 1초 주요 사건 최대 20개.

---

## 12. 모의 결제사 (mock-pg, :8081)

- **`POST /auth`** `{ orderId, amount }` → 확률 authFailureRate로 400 AUTH_FAILED, 성공 200 `{ paymentKey }` (AUTHORIZED 저장). 사람의 시간은 시뮬레이터가 호출 전에 기다린다.
- **`POST /payments/confirm`** `{ paymentKey, orderId, amount }` → 지연 U(confirmMinMs, confirmMaxMs). 확률 timeoutRate로 응답 없이 `confirmMaxMs + 10000`ms 대기 (이때도 내부 승인 여부를 50%로 정해 저장, 결과 불명 재현). 확률 declineRate로 400 INSUFFICIENT_FUNDS (DECLINED). 불일치·이미 처리 → 400 INVALID_REQUEST. 성공 200 `{ status: DONE, approvedAt }`.
- **`GET /payments/{key}`** → AUTHORIZED / DONE / DECLINED / CANCELED 또는 404.
- **`POST /payments/{key}/cancel`** → DONE/AUTHORIZED면 CANCELED, 멱등.
- **관리**: `GET/PUT /admin/config` `{ authFailureRate, declineRate, timeoutRate, confirmMinMs, confirmMaxMs, seed }`, `POST /admin/reset` (본문에 `anchorAt`이 있으면 3장의 Clock 기준을 잡는다), `GET /admin/stats` `{ authRequests, authFailed, confirmRequests, done, declined, timedOut, canceled, confirmInflight, confirmLatency }`, `GET /admin/payments`.

---

## 13. UI 공통

- 화면 2개: 헤더 오른쪽 탭 **실시간 · 실행 결과**. 실행이 끝나면 실시간 화면에 "결과 보기" → 그 실행을 선택한 결과 화면.
- 목업: `docs/ui-realtime.dc.html`, `docs/ui-results.dc.html`. 배치·색·동작을 따르되 목업 속 가짜 시뮬레이션·데이터 로직은 쓰지 않는다. **목업과 이 문서가 다르면 이 문서가 우선.**
- 1920×1080 기준. 작은 창은 화면 전체를 비율 유지 축소 (`transform: scale`, 가로 기준). 내부 스크롤 없음.
- 바닐라 JS + CSS (외부 라이브러리 없음). 요청 점 애니메이션은 `requestAnimationFrame` + `<canvas>` 오버레이, 노드는 DOM.
- 접근성: 버튼은 `<button>`, 입력은 `<label>` + `<input>`, 터치 대상 44px 이상. 서버에 연결이 안 되면 헤더에 경고 표시 (14.1). 색만으로 구분하지 않는다 (좌석은 라벨·막대·×n 병행).

**다크 테마 토큰**
| 토큰 | 값 | 용도 |
|---|---|---|
| bg | `#0C1016` | 바탕 (점 무늬 없음) |
| panel | `#1F2739` | 카드·노드 |
| panelInner | `#161D2B` | 카드 안 상자, 설정 패널 |
| border | `#3D4A64` | 테두리 |
| line | `#5F7099` | 연결선 |
| track | `#28324A` | 막대 바탕 |
| text / textSub / textMuted | `#EEF1F6` / `#B0B8C7` / `#929CAF` | 글자 |
| ok / warn / bad | `#3DDC97` / `#F5A524` / `#FF5D5D` | 상태 |
| hold / deposit / sold / auth | `#F5A524` / `#22B8CF` / `#4C8DFF` / `#9B7BFF` | 좌석·흐름 |
- 최소 글자 13px. 숫자 JetBrains Mono, 글 **Pretendard** (목업의 Noto Sans KR 대신).

**상세 창** (공통): 화면 중앙 860×760, 바탕 `rgba(5,7,11,0.62)`. 닫기 ✕·바탕 클릭·Esc. 열려 있어도 실행·갱신 계속.

---

## 14. 화면

### 14.1 실시간 화면
**헤더**: 제목, 상태 pill (실행 상태 · 시뮬레이션 시각 / 판매 종료 시각 · 배속), 단계 스텝퍼 6칸 (오픈 · 선점 폭주 · 취켓팅 · 매진 · 취소표 오픈 · 판매 종료, phase 기준. 현재 단계 색: 매진 빨강, 취소표 오픈 청록, 그 외 파랑), 탭. 계측이 끊긴 서버가 있으면 pill 옆 빨간 점 + 이름 (`connections`).

**서버 지표 카드 4개**
| 카드 | 큰 숫자 | 보조 | 클릭 |
|---|---|---|---|
| 처리량 (예약 서버) | signals.rps | "대기열 폴링 n rps 별도" | 예약 서버 상세 |
| 응답 시간 p95 | signals.p95 ms | "p50 · p99" | 예약 서버 상세 |
| 에러율 | signals.errPct % | "409 n/s · 403 n/s · 5xx n" | 에러 상세 |
| 포화도 (DB 풀) | signals.poolPct % | "대기 n · 락 대기 n" | DB 상세 |
- 오른쪽 최근 60초 막대. 숫자·막대·테두리 색은 `levels`.

**구조도** (1512×772 영역, 목업 배치)
| 노드 | 큰 숫자 | 보조 | 테두리 |
|---|---|---|---|
| 대기열 서버 (왼쪽 위) | 대기 인원 | 입장 n/정원, 입장키 발급 n/s, 대기 점 격자 (1점 = 8명) | queue.status p95 < 20ms 기본 / < 100 warn / 그 외 bad |
| 예약 서버 (가운데 위) | p95 ms | 처리 중 · rps, 최근 p95 막대 | levels p95·pool 중 나쁜 쪽 |
| 사용자 (왼쪽 아래) | - | 도착 전 / 입장 / 이탈 / 예매 완료 | 없음 |
| 모의 결제사 (가운데 아래) | 승인 p95 | 인증 중 · 실패 | p95 > confirmMaxMs warn, timeouts > 0 bad |
| DB (오른쪽) | 커넥션 풀 active/max | 풀 대기, 락 대기, 풀 칸, 좌석 상태 한 줄, 좌석 맵, 취소표 카운트다운·매진 배지 | levels.pool |

| 연결선 | 의미 | 두께 기준 | 색 |
|---|---|---|---|
| 사용자 ↔ 대기열 서버 | 순번 폴링 ↑, 입장키 ↓ | queue.status rps | line |
| 사용자 → 예약 서버 | 입장키로 직접 | 예약 서버 사용자 rps | levels.err bad `#B5505C` / warn `#9C6A5A` / ok line |
| 예약 서버 → DB | 쿼리 | seats+holds+입금 rps | line |
| 예약 서버 → 대기열 서버 | 자리 반납 알림 | 초당 알림 | `#3E9C75` |
| 예약 서버 → 결제사 | 승인 요청 | 승인 rps | `#4C6FB8` |
| 사용자 → 결제사 | 카드 인증 (직접) | 인증 중 ÷ 2 | `#7A64C8` |
- 두께 `3 + min(11, log2(1 + rps) × 1.4)` px. 선마다 라벨 1개 ("313 rps 폴링", "115 rps · 에러 2%", "승인 4/s", "자리 반납").
- **사용자 → 예약 서버 선은 입장키를 가진 사람만 쓴다.** 대기 중 요청은 이 선에 나오지 않는다.

**요청 점**: 도착(흰 회색)·순번 폴링(회색 작게) 사용자→대기열, 입장키(흰색) 대기열→사용자, 좌석 조회(회색)·선점(주황) 사용자→예약 서버→DB, 409(빨강)·403(어두운 빨강 작게) 예약 서버→사용자, 자리 반납(초록) 예약 서버→대기열, 카드 인증(보라) 사용자→결제사, 승인(파랑) 예약 서버→결제사. 점 수는 rps 비례, 선마다 최대 40개.

**좌석 맵** (DB 노드 안)
- 무대 띠, 왼쪽 등급 라벨 (VIP/S/A/B) 항상 표시.
- 좌석 수에 맞춰 칸 크기를 계산해 **전부 한 번에** 보이게 (스크롤 금지). 칸 최소 28×18px, 더 작으면 글자 숨김.

| 상태 | 표현 |
|---|---|
| 빈 좌석 | 등급색 테두리 + 등급색 바탕(알파 0.3) + 밝은 글자 |
| 선점 | 주황 + 남은 시간 막대 |
| 입금 대기 | 청록 점선 + 남은 기한 막대 |
| 반환 대기 | 회색 점선, 흐린 글자 |
| 판매 | 거의 검정 `#0C0F15`, 테두리 `#1C2230`, 어두운 글자 |
- 효과: 충돌 흔들림 + `×n` + 파동, 방금 풀림 초록 빛 (취소표 오픈 시 여러 칸 동시).

**읽는 법 줄**: "선 두께 = 초당 요청 · 선이 붉으면 에러 많음 · 테두리 색 = 포화도 · 노드·카드 클릭 → 상세".

**노드 상세** (상세 창, 카드 = 이름·값·설명 1줄)
| 대상 | 내용 |
|---|---|
| 대기열 서버 | 대기 인원, 입장 중/정원, 입장키 발급, 순번 응답 rps·p95, 자리 반납, busy, 만료 미룸 중(`slotsBusyOverTtl`, 정상), busy 상한 강제 만료 누적(`expiredByBusyCap`, 0 아니면 경고 색) |
| 예약 서버 | p50/p95/p99, 처리 중, 입장키 통과·403, 엔드포인트별 rps·p95 표, 스레드, 힙·GC, 스케줄러 4개, 알림 큐(대기·재시도·유실) |
| DB | 풀 active/max, 풀 대기, 획득 p95, 락 대기 수·최대 시간, 초당 충돌, 좌석 5상태 |
| 모의 결제사 | 인증 중, 승인 n/s, 승인 p50/p95/p99, 실패, 타임아웃, 결제 수단 비율 |
| 사용자 | 도착 전·입장·이탈·예매 완료, 대기 중·입장 중 성향 구성 (막대) |
| 에러 | 에러율, 실패율, 8.3 분류별 n/s |

**하단 띠** (높이 56px, 두 칸)
- 실시간 이벤트: 최신 1건 + "전체 n건 ›" → 상세 창 최근 80건, 필터 전체/선점/반환/결제/시스템.
- 누적 카운터: 반환 · 취소표 오픈 · 충돌 · Hardcore + "상세 ›" → 즉시 반환, 예매 취소, 취소표 오픈 횟수, 등급별 판매, 충돌 누적, Hardcore 잔류, 예매 완료, 카드 결제 실패 (설명 1줄씩).

**설정 패널** (오른쪽)
- 맨 위 실행 이름. 버튼: **시작**, **화면 고정**(표시만 멈춤, 상단에 "화면 고정 중 · 실행은 계속"), **실행 중지**, **초기화**(설정 기본값).
- 배속 1× / 2× / 4×. 시간 항목 옆에 현재 배속 기준 실제 시간 ("7분 · 실제 1분 45초"). 1×가 아니면 "부하 측정·전략 비교는 1×로".
- 탭: **기본**(좌석, 사용자 수, 도착 분포, 판매 시간, 등급·가격) / **시나리오**(대기열: 정원·초당 입장·세션·매진 시 대기열 닫기, 결제·취소표, 이탈 성향) / **고급**(관람객 속도, 매수, 결제·장애, 서버 전략·DB 방어선·시드, 계측 임계치).
- 프리셋 저장·불러오기. 실행 중 입력 비활성화.

### 14.2 실행 결과 화면
**상단**: 제목, 보기 전환 **단일 실행 / 전략 비교**, 실행 선택 (최근 3개 칩 + "모든 실행 ›" 서랍: 이름·전략·시각·상태·불변식 통과 수, 검색). 단일 1개, 비교 2개 (A = 먼저, B = 나중, 새로 고르면 오래된 쪽이 빠짐). 9.2 경고는 상단 노란 줄.

**요약 카드 6개**: 최대 p95 응답 / 폭주 구간 평균 p95 / 최대 처리량 / 풀 포화 시간 / 매진 시각(잔여 첫 0) / 불변식 (없으면 "검사 안 함"). 비교 모드는 A·B 값을 태그(A/B, 실행 색)와 함께 나란히, 나은 쪽 ok 색.

**그래프 4개 (2×2)**
| 그래프 | 단일 | 비교 | 기준선 |
|---|---|---|---|
| 처리량 | 선 1개 | A·B | - |
| 응답 시간 | p95 진하게 + p99 연하게 | A·B p95 | SLO 점선 |
| 에러율 | 전체 + conflict 비중 연하게 | A·B | warn·bad 점선 |
| DB 풀 사용률 | 사용률 + 풀 대기 막대 | A·B | 포화 점선 |
- 가로축 시뮬레이션 시간 (5분 눈금). 단계 음영 (폭주 빨강, 취켓팅 주황, 매진 회색, 취소표 청록, 알파 0.05~0.10, 좁으면 이름 생략). 최고점 원 + 값 (겹치면 비킴). 마우스 오버 시 세로 안내선 + 그 t의 값. 클릭 → 엔드포인트별 상세 (응답 시간은 서버/클라이언트 측 전환). 세로축 1·2·5 배수 눈금.

**오른쪽 패널**: 전략 비교 표(비교) / 요약 표(단일) — 9.3 순서, 열 지표·A·B·차이, 나은 쪽 ok 색 + 옅은 배경, 나빠진 차이 bad 색. 아래 판정 문장. 불변식 검사 (✓/✗, 실패 위로, 클릭 시 detail·evidence, 없으면 "검사 결과 없음 · 외부 검사기로 snapshot.json을 검사하세요"). 실행 조건 10개 (사용자, 좌석, 정원·입장, 세션, 배속, 시드, 도착 분포, 결제사 지연, 커넥션 풀, 동시성 전략), 비교 시 다른 항목 노랑 + "다른 건 n개".

**실행 관리**: 이름·메모 수정, 고정(📌, 자동 삭제 제외), 삭제(확인 창), "요약 JSON 내려받기".

---

## 15. 불변식 (외부 검사기 기준)

| ID | 불변식 |
|---|---|
| I1′ | 좌석 SOLD ⇔ 그 좌석을 가진(released_at IS NULL) CONFIRMED 예약 정확히 1건 |
| I2′ | 좌석당 released_at IS NULL인 reservation_seats 최대 1행 |
| I3 | mock-pg에서 DONE(취소 안 됨)인 결제 ⇔ 서버 APPROVED 결제 + CONFIRMED 예약 (돈만 빠지고 좌석 없는 경우 0) |
| I4 | 사용자당 HELD/CONFIRMING 최대 1건 |
| I5′ | 좌석 상태 합 = 전체. HELD ⇔ 소유 예약 HELD/CONFIRMING, PENDING_DEPOSIT ⇔ 소유 예약 PENDING_DEPOSIT, RETURN_PENDING ⇔ 열린 배치 소속 |
| I6 | 정지 상태(트래픽 종료 + TTL + confirmDeadline + 5초)에서 HELD/CONFIRMING 예약 0 |
| I7 | 같은 Idempotency-Key ⇒ 예약 1건, 같은 응답 본문 |
| I8″ | 대기열 서버: 어느 시점에도 자리 수 ≤ maxActive, 입장은 seq 순서, 주기당 입장 ≤ admitPerSec × interval (snapshot의 토큰 admittedAt·입장 기록·activeMax·admittedPerTickMax로 검사) |
| I9 | 승인 직전 확인에서 만료된 예약에 대해 mock-pg 승인 호출 0 |
| I11 | 한 예약의 좌석은 항상 같은 상태 (부분 선점·부분 반환 없음) |
| I12 | 사용자당 CONFIRMED+PENDING_DEPOSIT 예약 최대 1건, 좌석 합 ≤ maxSeatsPerUser |
| I13 | mock-pg DONE(취소 안 됨) 결제 ⇔ CARD 방식 CONFIRMED 예약. CANCELED 예약의 카드 결제는 mock-pg에서도 CANCELED |
| I14 | RETURN_PENDING 좌석은 release_at + 2초 안에 AVAILABLE (판매 종료 시 예외) |
| I15″ | 판매 종료 후 대기열 서버 WAITING·ADMITTED 0, 예약 서버 HELD 0 |
| I16 | busy 상한 강제 만료(`busyCapExpirations`)의 각 kid에 대해 (a) 예약 서버 `droppedNotifications`에 그 kid의 HOLD_CLEARED 또는 COMPLETED가 있거나 (b) 그 시각에 그 kid의 예약이 CONFIRMING이었다. 결제 중 TTL을 넘긴 busy 자체는 위반이 아니다 |
| I17 | 예약 서버 `counters.acceptedInvalidKeys` = 0 |
- ID는 외부 검사기와 맞춘다 (프라임 포함). I10은 폐기.

---

## 16. 테스트

server 통합 테스트는 **Testcontainers Postgres**. 시간은 `MutableClock`. mock-pg·대기열 서버는 테스트에서 스텁 서버로 대체 가능. 가짜 응답은 `contracts/`에서 만든다 (0장 5번).

**server**
- 선점: 201(좌석·등급·가격), 없는 좌석 404, 선점된 좌석 409 + unavailableSeatIds, 두 번째 선점 409 USER_ALREADY_HOLDING, 예매한 사용자 409 USER_ALREADY_PURCHASED. release 후 재선점, release 멱등. 멱등 키 같은 본문 / 다른 내용 422.
- 2매: 둘 다 빈 좌석 → 성공. 하나만 빈 좌석 → 409, 아무것도 안 잡힘.
- 동시성: conditional/pessimistic/optimistic 각각 backstop off, 200 스레드 같은 좌석 → 성공 1, 충돌 199. 같은 두 좌석을 역순으로 요청하는 200 스레드 → 교착 없음, 좌석마다 성공 1. 같은 사용자 50좌석 동시 → 성공 1. naive는 결과 출력만.
- 4.3 전이표 각 행. 만료 예약 confirm → 409 + 결제사 승인 호출 0. 승인 타임아웃 + 조회 DONE/AUTHORIZED/실패 각각. CONFIRMING 중 TTL 경과해도 만료 안 됨. 만료 좌석을 남이 잡은 뒤 옛 예약 confirm → 409. checkout 재요청 같은 orderId.
- 입금 대기 → 입금 → SOLD. 기한 경과 → RETURN_PENDING → returnDelay 후 일괄 AVAILABLE. 미입금 2건 시간차 → 같은 배치.
- 예매 취소: 카드 → mock-pg cancel, 즉시 반환. 결제사 실패 → 502, 상태 그대로.
- 판매 종료: 이후 `/seats`·새 `/holds` 409 SALE_ENDED, 기존 예약 confirm·입금·취소 처리, HELD 만료. 종료 전 `/seats`에 요약 필드 8개 (실제 서버, 계약 파일과 대조).
- 입장키: 6.3 표 각 실패 코드, 만료 키 + HELD 예약 → checkout·confirm 통과·새 holds 403, COMPLETED 후 같은 키 → KEY_REVOKED. 독립 재검증 카운터.
- SlotNotifier: 대기열 서버 다운 → 재시도 3회 후 dropped, `droppedNotifications` 기록, 요청은 성공. 만료 스케줄러 → HOLD_CLEARED. 복구 스케줄러 CONFIRMED → COMPLETED.
- timeScale 4: TTL·기한이 1/4 실제 시간.
- 시계 기준점: 단위 테스트 — 가짜 nanoTime + 6% 빠른 가짜 벽시계에서 Clock이 anchor + 단조 경과를 따르는지, reset 전후·재reset 동작. 통합 테스트 — `anchorAt`을 1시간 전 값으로 보내면 서버 시각이 anchorAt + 경과 ±100ms (시스템 시계를 직접 쓰는 코드가 남아 있으면 1시간 차이로 드러남).
- reset: 좌석 수·등급 배정, grades 불일치 400.
- 계측: 고정 지연 엔드포인트의 p95 범위, 에러 분류 카운트, pool.acquireMs, cumulative.

**queue**
- 정원 200·초당 20, 1,000명 → 주기당 ≤ 20, 자리 ≤ 200, seq 순서. 반납 후 다음 주기 입장. 재진입 맨 뒤.
- 입장키 서명 성공/위조/다른 runEpoch/만료.
- busy: HOLD_ACTIVE 후 TTL 지나도 유지(`slotsBusyOverTtl` 1, `expiredByBusyCap` 0) → HOLD_CLEARED 후 EXPIRED. HOLD_CLEARED 유실 → busy 상한에서 EXPIRED, `expiredByBusyCap` +1, `busyCapExpirations` 기록.
- 이벤트 순서 뒤바뀜 무시, COMPLETED 이후 무시.
- closeQueueOnSoldOut true/false, soldOut=false 후 재접수. 판매 종료 → 전원 CLOSED, 이후 enter CLOSED(SALE_ENDED).
- 내부 API 비밀 틀리면 401, 없는 kid ignored. `/queue/status`에 좌석 상황 필드 없음.

**mock-pg**: seed 고정 시 실패율 비율, 타임아웃 지연, cancel 멱등, confirmLatency.

**simulator**
- 좌석 선택 (앞줄 우선, 가운데 가중치, n매 연석·대체), 페르소나·도착·성향 샘플링 비율.
- 이탈 성향 (casual 시점 분포, persistent 반감 확률, hardcore 재진입), 대기 중 이탈 없음, 재방문 확률.
- 시계 기준점: 세 reset의 anchorAt이 같음, saleEndAt·startedAt·t가 anchor 기준, 시뮬레이터 안 시간 판단이 anchor 시계 기준.
- 실제 짧은 실행 (1×, saleDurationSec=120): 판매 종료 t가 118~122, clockOffsetsMs 전부 500ms 이하. **고치기 전 코드로 먼저 돌려 실패(약 113초)하는 것을 확인한 뒤** 고친다.
- 판매 종료 인식: SALE_ENDED / phase=ENDED로 종료, 보조 경로는 정상 응답이 없을 때(transport·timeout·5xx)만, `saleEndFallback` 카운트. 기본 경로와 보조 경로를 각각 빼면 실패하는지.
- 사전 점검: 대상 다운 → TARGET_DOWN, 기록 안 생김. reset 실패 → FAILED.
- 저장: 파일 5개(+ invariants), timeseries 줄 수 ≈ 실행 초, 깨진 구버전 파일이 있어도 목록 성공 + warnings.
- 비교: fingerprint 같음/다름, 9.2의 경고 전부(현재 8종), diffPct·winner, 다운샘플 규칙.
- 요약 JSON 집계 (가짜 응답 시퀀스).

---

## 17. 구현하지 말 것

| ID | 제외 |
|---|---|
| T1 | 목표 rps 제어(open-loop), 도착률 기반 부하 |
| T4 | 서버 다중 인스턴스, Redis, 분산 락 |
| T6 | 결제사 호출 재시도·백오프·서킷 브레이커 (6.6의 조회 1회 + 망취소 + 복구 스케줄러까지만) |
| T7 | 대기열 Redis 이전, 봇·매크로 차단, rate limit, 공정성 정책(사전 대기열 무작위화 등), 입장 속도 자동 조절 |
| - | 실행 리플레이 |
| - | 과부하 대응(429/503 요청 버리기) — 분류 자리만 있음 |
| - | 인증/인가, 실결제, 앱 Docker 이미지, Kafka, Kubernetes |

---

## 18. 구현 단계와 현재 상태

| 단계 | 범위 | 상태 |
|---|---|---|
| S1~S6, V1~V6 | 기본 기능, v0.4 도메인 (다매, 입금, 취소표, 이탈 성향, 배속, 화면 v0.4) | 완료 |
| W1 | 정리 (standbyLimit 제거, 대기열 응답 좌석 필드 제거, closeQueueOnSoldOut, 이탈 입장 후) | 완료 |
| W2 | 계측 8.1~8.4, 실행 기록 8.5, 사전 점검 10.2, 실행 API·비교 9장 | 완료 (EMBEDDED 기준선 남김) |
| **W3** | 대기열 서버 7장, 예약 서버 6.3·6.10·6.11, 내장 대기열 제거·V3, 사용자 흐름 11.2~11.3, 대기열 계측 | **진행 중** — 남은 것: 시계 기준점(3장) 적용, bench 환경(10.4)에서 새 기준선. 완료 기준: 16장 해당 테스트 + bench에서 1× 기본 실행 2개(conditional, pessimistic)를 새 기준선으로 남김 — 둘 다 판매 종료 t = 1200 ± 2, 둘의 비교에서 경고 없음·configDiff는 strategy 하나. W2 기준선(EMBEDDED)과의 비교는 **참고용**으로 한 번 돌려 결과만 보고 (예전 시계·bench 밖 실행이라 CLOCK_MODEL_DIFFERS, ENV_DIFFERS는 예상된 경고. 분리 효과는 폭주 구간 지표로만 해석) |
| W4 | 실시간 화면 13·14.1 | 대기 — 완료 기준: 19장 2번 |
| W5 | 실행 결과 화면 14.2 | 대기 — 완료 기준: 19장 3~7번 + 1920×1080, 1440×860 스크린샷 |

---

## 19. 수동 확인

1. 다섯 프로세스 실행 (2.1), **1×**, 기본 설정, 이름 "조건부 기본".
2. 실시간 화면
   - 대기 중에는 사용자 → 예약 서버 선에 점이 없고, 대기열 서버 선만 굵다.
   - 폭주 때 예약 서버·DB 테두리 노랑/빨강, 사용자 → 예약 서버 선이 굵고 붉다.
   - VIP부터 좌석이 차고, 충돌이 앞줄 가운데에서 시작해 뒤로 번진다.
   - 입금 대기 칸 → 미입금 → 반환 대기 → 카운트다운 → 여러 칸 동시 오픈 → 재방문.
   - 매진 중 Hardcore 잔류, 예매 취소로 한 석씩 즉시 풀림, 판매 종료 시 전원 퇴장하고 `/seats` 트래픽이 멈춘다.
   - 노드 5개·카드 4개·하단 띠 2개 클릭 → 상세 창, Esc로 닫힘.
   - 1440×860 창에서 전체 축소, 스크롤 없음.
3. 끝나면 "결과 보기" → 그래프 4개, 단계 음영, 요약 카드.
4. 전략만 비관적 락으로 "비관적 기본" 실행 → 비교: 경고 없음, "다른 건 1개 (동시성 전략)", 표·판정 문장.
5. 4×로 한 번 더 → 1×와 비교하면 TIME_SCALED 경고.
6. 실행 중 대기열 서버 강제 종료 → 입장한 사용자는 계속, 새 진입 transport 실패, 연결 끊김 표시, 5초 후 FAILED(METRICS_LOST).
7. 외부 검사기 없이 결과 화면 → 불변식 "검사 안 함".
8. 종료 후 등식: 요약 `seatsSold` = `/admin/stats` SOLD, mock-pg DONE − CANCELED = CARD CONFIRMED 예약의 결제 수. 승인 타임아웃 30%로 다시 실행해도 유지.

---

## 부록 A. 폐기된 규칙 (예전 문서에서 보이면 무시)

| 폐기 | 이유 |
|---|---|
| 웹훅 결제 (v0.2) | 국내식 2단계로 교체 |
| `standbyLimit`, 취소표 대기 정리, `STANDBY_FULL`, `standbyTrimmed`, I10 | 실제 서비스 패턴 아님, 이탈 성향과 중복 |
| 매진 시 WAITING 전원 CLOSED, 매진 후 진입 즉시 CLOSED | `closeQueueOnSoldOut`(기본 false)로 대체 |
| 잔여석 연동 입장 `resaleAdmitMultiplier`, 재진입 제한 `NO_SEATS` (v0.3.1) | 비현실적 |
| 대기열 응답의 좌석 상황 필드 | 대기자는 좌석 상황을 모른다 (`/seats`에는 있음) |
| 대기 중 이탈 판단 | 이탈은 입장 후에만 |
| `earlyQuitRate`(일찍 포기형) | 이탈 성향으로 대체. 난수 1회는 순서 유지용으로 남김 (10.3) |
| 예약 서버 내장 대기열, `queue_tokens`, `X-Queue-Token`, `NOT_ADMITTED`, `queue.enabled` | 대기열 서버 분리 (입장키 방식) |
| `/queue/enter` 판매 종료 시 409 | 200 + CLOSED(SALE_ENDED) |
| 시나리오 KPI 카드 6개, 하단 큰 패널 | 서버 지표 카드 4개, 하단 띠 + 상세 창 |
| `docs/ui-reference.dc.html` | `ui-realtime`, `ui-results`로 교체 |
