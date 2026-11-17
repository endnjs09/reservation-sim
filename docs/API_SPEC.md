# reservation-sim API 명세

> 기준 문서는 `SPEC.md`(1.4.1)다. 이 문서는 SPEC의 6·7·8.4·9·11.9·12장을 엔드포인트 단위로 펼친 것이다. 둘이 다르면 **SPEC.md가 맞고** 이 문서를 고친다.
> SPEC에 응답 본문이 정해지지 않은 곳은 이 문서가 정한다 (구현 코드와 일치시킴).
> 변경 규칙: 필드 **추가만** 허용한다 (SPEC 0장 9번). 필드 삭제·이름 변경·의미 변경은 SPEC 개정이 먼저다.

---

## 1. 서비스와 기본 주소

| 서비스 | 모듈 | 기본 주소 | 사용자 | 저장소 |
|---|---|---|---|---|
| 예약 서버 | `server` | `http://localhost:8080` | 가상 사용자, 시뮬레이터(관리) | PostgreSQL |
| 대기열 서버 | `queue` | `http://localhost:8082` | 가상 사용자, 예약 서버(내부), 시뮬레이터(관리) | 메모리 |
| 모의 결제사 | `mock-pg` | `http://localhost:8081` | 가상 사용자(인증), 예약 서버(승인·조회·취소), 시뮬레이터(관리) | 메모리 |
| 시뮬레이터 | `simulator` | `http://localhost:8090` | 브라우저 UI | 파일 (`runs/`, `presets/`) |

측정 환경(bench, SPEC 10.4)에서는 같은 API를 18180~18190 대역 포트로 띄운다. 경로·본문은 같다.

### 1.1 호출 관계

```
가상 사용자 ──▶ 대기열 서버   POST /queue/enter, GET /queue/status, POST /queue/leave
가상 사용자 ──▶ 예약 서버     GET /seats, /holds*, /payments/confirm, /deposits/*, /reservations/*
가상 사용자 ──▶ 모의 결제사   POST /auth
예약 서버   ──▶ 대기열 서버   POST /internal/slots/{kid}/events, POST /internal/sale-state
예약 서버   ──▶ 모의 결제사   POST /payments/confirm, GET /payments/{key}, POST /payments/{key}/cancel
시뮬레이터  ──▶ 세 서버       /actuator/health, /admin/*
브라우저    ──▶ 시뮬레이터     /api/*
```
- 대기자의 순번 폴링은 예약 서버에 닿지 않는다.
- 예약 서버는 사용자 요청을 처리하면서 대기열 서버를 **동기 호출하지 않는다** (입장키는 자체 검증, 알림은 커밋 후 비동기).

---

## 2. 공통 규약

### 2.1 형식
| 항목 | 규칙 |
|---|---|
| 본문 | `application/json; charset=UTF-8` |
| 시각 | ISO-8601 UTC 문자열 (`2026-10-06T08:34:30.522Z`). 서버의 "현재 시각"은 anchor 시계 (SPEC 3장) |
| 시간 길이 | 설정값의 `...Sec`는 **시뮬레이션 초** (실제 적용 = 값 ÷ timeScale). `...Ms`는 실제 ms |
| 숫자 | 금액·개수는 정수. 비율(`...Rate`)은 0~1 실수, 퍼센트(`...Pct`)는 0~100 |
| null | 값이 없으면 필드를 빼지 않고 `null` |
| 알 수 없는 필드 | 요청의 모르는 필드는 무시한다 |

### 2.2 인증 방식
| 방식 | 헤더 | 적용 | 검증 |
|---|---|---|---|
| 입장키 | `X-Admission-Key: v1.<payload>.<sig>` | 예약 서버 `GET /seats`, `/holds*`, `POST /payments/confirm` | 예약 서버가 HMAC으로 자체 검증 (4.1) |
| 내부 비밀 | `X-Internal-Secret: <internal.secret>` (+ 선택 `X-Run-Epoch`) | 대기열 서버 `/internal/**` | 비밀은 상수 시간 비교, 틀리면 401. `X-Run-Epoch`가 현재 실행과 다르면 이전 실행의 늦은 알림으로 보고 무시 |
| 없음 | - | 그 외 사용자 API, 모든 `/admin/**`, 시뮬레이터 API | 로컬 실험 도구라 인증 없음 (SPEC 1장, 17장) |

### 2.3 에러 응답
```json
{ "code": "SEAT_UNAVAILABLE", "message": "seat 37 is not available", "unavailableSeatIds": [37] }
```
- 공통으로 `code`(대문자 스네이크)가 있다. `message`(사람용, 형식 보장 없음)는 예약 서버·대기열 서버·시뮬레이터에 있고, 모의 결제사는 `code`만 준다. 코드별 추가 필드는 아래 표.

### 2.4 에러 코드

**예약 서버** (SPEC 6.2)
| code | HTTP | 의미 | 추가 필드 |
|---|---|---|---|
| VALIDATION_FAILED | 400 | 요청 형식 오류 | - |
| KEY_INVALID | 403 | 입장키 형식·서명·runEpoch·userId 불일치 | - |
| KEY_EXPIRED | 403 | 입장키 만료 (결제 도중 예외 제외) | - |
| KEY_REVOKED | 403 | 구매를 끝낸 키 | - |
| SEAT_NOT_FOUND | 404 | 없는 좌석 | - |
| RESERVATION_NOT_FOUND | 404 | 없는 예약 | - |
| SEAT_UNAVAILABLE | 409 | 이미 선점·판매된 좌석 | `unavailableSeatIds: number[]` |
| USER_ALREADY_HOLDING | 409 | 이미 HELD/CONFIRMING 예약 있음 | - |
| USER_ALREADY_PURCHASED | 409 | 이미 예매함 (CONFIRMED/PENDING_DEPOSIT) | - |
| RESERVATION_NOT_PAYABLE | 409 | HELD 아님, 만료, 다른 사용자, 금액 불일치 | - |
| DEPOSIT_NOT_ACCEPTABLE | 409 | 입금 불가 (상태·기한·금액) | - |
| RESERVATION_NOT_CANCELABLE | 409 | 취소 불가 상태 | - |
| SALE_ENDED | 409 | 판매 종료 | - |
| RATE_LIMITED | 429 | 좌석 조회 새로고침 제한 | `retryAfterMs: number` + `Retry-After` 헤더(초, 올림) |
| PAYMENT_DECLINED | 402 | 결제사 승인 거절 | - |
| IDEMPOTENCY_KEY_REUSED | 422 | 같은 멱등 키, 다른 내용 | - |
| PG_UNAVAILABLE | 502 | 결제사 호출 실패 (checkout·취소) | - |

**대기열 서버**
| code | HTTP | 의미 |
|---|---|---|
| VALIDATION_FAILED | 400 | 형식 오류, 없는 토큰 |
| UNAUTHORIZED | 401 | 내부 비밀 불일치 |

**모의 결제사** (SPEC 12장)
| code | HTTP | 의미 |
|---|---|---|
| AUTH_FAILED | 400 | 카드 인증 실패 (authFailureRate) |
| INSUFFICIENT_FUNDS | 400 | 승인 거절 (declineRate) |
| INVALID_REQUEST | 400 | 불일치, 이미 처리됨 |
| NOT_FOUND | 404 | 없는 paymentKey |
| INTERRUPTED | 503 | 승인 처리 중 스레드 중단 (정상 운영에서는 발생하지 않음) |

**시뮬레이터**
| code | HTTP | 의미 | 추가 필드 |
|---|---|---|---|
| RUN_ACTIVE | 409 | 이미 실행 중 / 실행 중인 기록 삭제 | - |
| TARGET_DOWN | 409 | 대상 서버 health 실패 (SPEC 10.2) | `target` |
| INVALID_CONFIG | 400 | RunConfig·프리셋 오류 (등급 행 합 ≠ rows 등) | - |
| RUN_NOT_FOUND | 404 | 없는 runId, 없는 invariants.json | - |
| STORAGE_ERROR | 500 | 실행 기록 파일 읽기·쓰기 실패 | - |

### 2.5 에러 분류 (계측용, SPEC 8.3)
응답은 서버 계측과 시뮬레이터 집계에서 아래로 분류된다. API 소비자가 의미를 해석할 때 기준이 된다.

| 분류 | 응답 | 해석 |
|---|---|---|
| conflict | 409 SEAT_UNAVAILABLE, USER_ALREADY_HOLDING | 경쟁의 결과 (고장 아님) |
| notPayable | 409 RESERVATION_NOT_PAYABLE, DEPOSIT_NOT_ACCEPTABLE, SALE_ENDED 등 그 외 409 | 상태 불일치 |
| key | 403 KEY_* | 입장키 문제 |
| declined | 402 | 결제 거절 |
| client | 400, 404, 422 | 요청 오류 |
| rateLimited | 429 RATE_LIMITED | 정상 제한. **에러율·실패율의 분자·분모 모두에서 제외** |
| shed | 그 외 429, 503 | 자리만 있음 (과부하 대응은 범위 밖) |
| server | 5xx | 서버 고장 |
| timeout / transport | (클라이언트 측) | 응답 없음, 연결 실패 |

### 2.6 공통 타입

| 타입 | 형식 | 비고 |
|---|---|---|
| `UserId` | 문자열 1~64자 | 시뮬레이터는 `u-0001` 형식. 인증 없이 식별만 (SPEC 1장) |
| `SeatId` | 정수 1 ~ rows×cols | `id = 행 인덱스 × cols + 열 인덱스 + 1` |
| `SeatLabel` | 문자열 | 행 문자 + 열 번호 (`A1`) |
| `ReservationId` | 정수(64비트) | |
| `OrderId` | UUID 문자열 | = payments.id |
| `PaymentKey` | 문자열 ≤ 64자 | 모의 결제사 발급 |
| `IdempotencyKey` | 문자열 1~64자 | |
| `AdmissionKey` | `v1.<payload>.<sig>` | 4.1 |
| `Kid` | UUID 문자열 | 입장키 ID |
| `RunEpoch` | 문자열 | 실행마다 새 값 |
| `SeatStatus` | `AVAILABLE` `HELD` `PENDING_DEPOSIT` `RETURN_PENDING` `SOLD` | seatMap 코드 A/H/D/R/S |
| `ReservationStatus` | `HELD` `CONFIRMING` `CONFIRMED` `EXPIRED` `RELEASED` `PAYMENT_FAILED` `PENDING_DEPOSIT` `DEPOSIT_EXPIRED` `CANCELED` | |
| `PaymentStatus` | `REQUESTED` `APPROVED` `FAILED` `CANCELED` | 예약 서버 쪽 결제 |
| `PgPaymentStatus` | `AUTHORIZED` `DONE` `DECLINED` `CANCELED` | 모의 결제사 쪽 |
| `PaymentMethod` | `CARD` `DEPOSIT` | |
| `Phase` | `OPEN` `RUSH` `RESALE` `SOLD_OUT` `REOPEN` `ENDED` | SPEC 6.12 |
| `TokenStatus` | `WAITING` `ADMITTED` `COMPLETED` `LEFT` `EXPIRED` `CLOSED` | 대기열 토큰 |
| `CloseReason` | `SALE_ENDED` `SOLD_OUT` | |
| `Grade` | `{ name: string, rows: int, price: int }` | 행 수 합 = rows |

---

## 3. 계약 파일

API 응답 형태는 실제 서버 테스트로 검증하고, 가짜 응답은 같은 계약 파일에서 만든다 (SPEC 0장 5번).

| 파일 | 대상 |
|---|---|
| `contracts/seats-response.json` | `GET /seats` 200 (요약 필드 8개 필수) |
| `contracts/seats-rate-limited.json` | `GET /seats` 429 |

새 계약 파일을 추가할 때는 이 표와 해당 엔드포인트 절에 함께 적는다.

---

## 4. 예약 서버 (:8080)

### 4.1 입장키 검사 (`AdmissionKeyFilter`)
**대상**: `GET /seats`, `POST /holds`, `POST /holds/{id}/release`, `POST /holds/{id}/checkout`, `POST /holds/{id}/deposit`, `POST /payments/confirm`.
**검사 없음**: `POST /deposits/{id}/pay`, `POST /reservations/{id}/cancel`, `GET /reservations/{id}`, `/admin/**`, `/actuator/**`.

**키 형식** (대기열 서버가 발급, SPEC 7.3)
```
v1.<base64url(payload)>.<base64url(HMAC-SHA256(keySecret, "v1." + base64url(payload)))>    패딩 없음
payload = { "kid": "<UUID>", "uid": "<userId>", "iat": <epoch ms>, "exp": <epoch ms>, "run": "<runEpoch>" }
```

| 순서 | 검사 | 실패 |
|---|---|---|
| 1 | 형식, 서명 (`MessageDigest.isEqual`) | 403 KEY_INVALID |
| 2 | `run` = 현재 runEpoch | 403 KEY_INVALID |
| 3 | `uid` = 요청 본문 `userId` (`GET /seats`는 생략) | 403 KEY_INVALID |
| 4 | `kid`가 회수 목록에 없음 | 403 KEY_REVOKED |
| 5 | `now < exp` | 아래 예외가 아니면 403 KEY_EXPIRED |

- **결제 도중 예외**: 키가 만료돼도 그 사용자에게 HELD/CONFIRMING 예약이 있으면 그 예약의 `checkout`, `deposit`, `release`, `POST /payments/confirm`은 통과. `GET /seats`와 새 `POST /holds`는 막는다.
- **독립 재검증**: 보호 대상 요청이 2xx로 끝나면 별도 구현(`AdmissionKeyAuditor`)이 같은 규칙으로 다시 확인하고, 실패면 `counters.acceptedInvalidKeys` +1 (불변식 I17).
- `admission.required = false`면 검사 생략 (단위 테스트용).

---

### 4.2 `GET /seats` — 좌석 조회

| 항목 | 내용 |
|---|---|
| 인증 | 입장키 |
| 처리 순서 | 입장키 검사 → 새로고침 제한 → 캐시 → DB |
| 트랜잭션 | 없음 (약간 오래된 값 허용) |
| 계약 | `contracts/seats-response.json`, `contracts/seats-rate-limited.json` |

**응답 200**
```json
{ "rows": 10, "cols": 10,
  "grades": [ { "name": "VIP", "rows": 1, "price": 150000 } ],
  "seats": [ { "id": 1, "label": "A1", "grade": "VIP", "status": "AVAILABLE" } ],
  "availableSeats": 0, "heldSeats": 10, "pendingDepositSeats": 4, "returnPendingSeats": 2,
  "soldOut": false, "releaseAt": "2026-10-06T08:36:12Z", "saleEndAt": "2026-10-06T08:54:30Z", "phase": "RESALE" }
```
| 필드 | 타입 | 설명 |
|---|---|---|
| rows, cols | int | 좌석 배치 |
| grades | Grade[] | 앞줄부터 행 단위 |
| seats | `{ id, label, grade, status }[]` | 전 좌석, id 오름차순 |
| availableSeats | int | AVAILABLE 수 **(필수)** |
| heldSeats | int | HELD 수 **(필수)** |
| pendingDepositSeats | int | PENDING_DEPOSIT 수 **(필수)** |
| returnPendingSeats | int | RETURN_PENDING 수 **(필수)** |
| soldOut | boolean | AVAILABLE 0 **그리고** HELD 0 **(필수)** |
| releaseAt | Instant \| null | 열린 취소표 배치의 오픈 시각 **(필수)** |
| saleEndAt | Instant | 판매 종료 시각 **(필수)** |
| phase | Phase | **(필수)** |

**에러**: 403 KEY_INVALID / KEY_REVOKED / KEY_EXPIRED, 409 SALE_ENDED, 429 RATE_LIMITED.

**새로고침 제한** (기본 켬, SPEC 6.4.1)
- 같은 `kid`로 `seatsMinIntervalSec`(기본 1.0, 시뮬레이션 초) 안에 다시 조회하면 429.
```json
{ "code": "RATE_LIMITED", "message": "...", "retryAfterMs": 640 }
```
- 헤더 `Retry-After: 1` (초, 올림). kid별 마지막 조회 시각은 메모리, `/admin/reset` 때 비움.
- 다른 엔드포인트에는 적용하지 않는다.

**캐시** (선택, 기본 끔, SPEC 6.4.2)
- `seatsCacheSec = 1`이면 응답 전체를 1초(시뮬레이션) 동안 모든 사용자에게 재사용. 선점·결제·취소는 캐시와 무관하게 DB에서 조건부 처리.

---

### 4.3 `POST /holds` — 선점

| 항목 | 내용 |
|---|---|
| 인증 | 입장키 |
| 헤더 | `Idempotency-Key` (필수, 1~64자) |
| 트랜잭션 | 1개 (DB_SPEC 6.2) |
| 멱등 | 같은 키 + 같은 내용 → 처음과 같은 201 본문. 같은 키 + 다른 내용 → 422 |

**요청**
```json
{ "userId": "u-0001", "seatIds": [37, 38] }
```
| 필드 | 타입 | 규칙 |
|---|---|---|
| userId | UserId | 필수, 입장키 `uid`와 같아야 함 |
| seatIds | SeatId[] | 1 ~ maxSeatsPerUser(4)개, 중복 금지 |
| seatId | SeatId | `seatIds` 대신 단일 좌석으로 보낼 때 (1개로 취급). **`seatId`와 `seatIds` 중 정확히 하나**만 보낸다 (둘 다·둘 다 없음 → 400) |

**응답 201**
```json
{ "reservationId": 812, "seats": [ { "id": 37, "label": "D7", "grade": "A", "price": 90000 },
                                   { "id": 38, "label": "D8", "grade": "A", "price": 90000 } ],
  "totalPrice": 180000, "status": "HELD", "holdExpiresAt": "2026-10-06T08:41:30Z" }
```

**에러**
| HTTP | code | 조건 |
|---|---|---|
| 400 | VALIDATION_FAILED | seatIds 비었음·개수 초과·중복, 헤더 없음 |
| 403 | KEY_* | 4.1 |
| 404 | SEAT_NOT_FOUND | 없는 좌석 |
| 409 | SALE_ENDED | 판매 종료 이후 |
| 409 | USER_ALREADY_PURCHASED | 이미 CONFIRMED/PENDING_DEPOSIT 예약 있음 |
| 409 | USER_ALREADY_HOLDING | 이미 HELD/CONFIRMING 예약 있음 |
| 409 | SEAT_UNAVAILABLE | 한 좌석이라도 못 잡음 → **아무것도 잡지 않음**, `unavailableSeatIds` |
| 422 | IDEMPOTENCY_KEY_REUSED | 같은 키, 다른 내용 |

**동작**
- 전부 아니면 전무. 잠금은 항상 seat id 오름차순.
- 동시성 전략(`strategy`)에 따라 좌석 확보 방식이 다르다 (DB_SPEC 6.2). 응답 형태는 전략과 무관하게 같다.
- 성공 커밋 후 대기열 서버에 `HOLD_ACTIVE` 알림 (4.14).
- 실패한 좌석마다 좌석별 충돌 카운터 +1 (계측 `seatConflicts`).

---

### 4.4 `POST /holds/{id}/release` — 선점 취소

| 항목 | 내용 |
|---|---|
| 인증 | 입장키 (결제 도중 예외 적용) |
| 멱등 | 이미 HELD가 아니면 200으로 현재 상태 |

**요청** `{ "userId": "u-0001" }`
**응답 200**
```json
{ "status": "RELEASED" }
```
- HELD이고 사용자가 같으면 RELEASED, 좌석 즉시 반환. 이미 HELD가 아니면 그때의 `status`를 그대로 담아 200.

**에러**: 400, 403 KEY_*, 404 RESERVATION_NOT_FOUND, 409 RESERVATION_NOT_PAYABLE (다른 사용자의 예약).

---

### 4.5 `POST /holds/{id}/checkout` — 결제 주문 생성 (카드 ①단계)

| 항목 | 내용 |
|---|---|
| 인증 | 입장키 (결제 도중 예외 적용) |
| 멱등 | 결제가 이미 있으면 같은 주문을 반환 |
| 외부 호출 | 없음 |

**요청** `{ "userId": "u-0001" }`
**응답 200** `{ "orderId": "6f1c…", "amount": 180000 }` (금액 = 좌석 가격 합)

**에러**: 403 KEY_*, 404 RESERVATION_NOT_FOUND, 409 RESERVATION_NOT_PAYABLE (사용자 불일치, HELD 아님, 만료).

---

### 4.6 `POST /payments/confirm` — 결제 승인 (카드 ③·④단계)

| 항목 | 내용 |
|---|---|
| 인증 | 입장키 (결제 도중 예외 적용) |
| 외부 호출 | 모의 결제사 승인 (트랜잭션 밖), 결과 불명 시 상태 조회 1회, 필요 시 망취소 |
| 트랜잭션 | A(확인·CONFIRMING) → 결제사 호출 → B(결과 반영) (DB_SPEC 6.4) |

**요청**
```json
{ "userId": "u-0001", "orderId": "6f1c…", "paymentKey": "pk_93ab…", "amount": 180000 }
```
| 필드 | 타입 | 규칙 |
|---|---|---|
| userId | UserId | 필수, 1~64자 |
| orderId | OrderId (UUID) | 필수 |
| paymentKey | 문자열 | 필수, 1~64자 |
| amount | int | 필수, 0 이상 |

**응답**
| HTTP | 본문 | 조건 |
|---|---|---|
| 200 | `{ "status": "CONFIRMED" }` | 승인 DONE (또는 조회 결과 DONE) |
| 202 | `{ "status": "CONFIRMING" }` | 결과 불명 — 사용자는 `GET /reservations/{id}`를 폴링 |
| 402 | `PAYMENT_DECLINED` | 거절, 또는 조회 결과 CANCELED/404, 또는 망취소 성공 |
| 409 | `RESERVATION_NOT_PAYABLE` | 사용자 불일치, HELD 아님, 만료, 금액 불일치, 결제 REQUESTED 아님 — **결제사 승인 호출 없음**. 만료된 좌석을 다른 사용자가 이미 잡은 뒤 옛 예약으로 승인하는 경우도 여기서 막힌다 |

**결과 불명 처리** (타임아웃·5xx·연결 실패 시)
| 결제사 `GET /payments/{key}` | 처리 | 응답 |
|---|---|---|
| DONE | 성공 처리 | 200 |
| CANCELED / 404 | 결제 CANCELED(404면 FAILED), 예약 PAYMENT_FAILED, 좌석 반환 | 402 |
| AUTHORIZED / DECLINED | 망취소 → 성공: 402 / 실패: CONFIRMING 유지 | 402 / 202 |
| 조회 실패 | CONFIRMING 유지 | 202 |

- CONFIRMING으로 남은 예약은 복구 스케줄러가 결론을 낸다 (SPEC 6.6).
- 성공(CONFIRMED) 시 대기열 서버에 `COMPLETED` 알림, kid 회수.

---

### 4.7 `POST /holds/{id}/deposit` — 입금 대기 선택

| 항목 | 내용 |
|---|---|
| 인증 | 입장키 (결제 도중 예외 적용) |
| 외부 호출 | 없음 (가상계좌는 흉내만) |

**요청** `{ "userId": "u-0001" }`
**응답 200**
```json
{ "status": "PENDING_DEPOSIT", "depositDeadline": "2026-10-06T08:37:30Z", "amount": 180000 }
```
- 예약 PENDING_DEPOSIT, 좌석 PENDING_DEPOSIT, `deposit_deadline = now + depositDeadlineSec`.
- 대기열 서버에 `COMPLETED` 알림 (입장 자리 반납), kid 회수.

**에러**: 403 KEY_*, 404 RESERVATION_NOT_FOUND, 409 RESERVATION_NOT_PAYABLE (HELD 아님, 만료, 다른 사용자).

---

### 4.8 `POST /deposits/{reservationId}/pay` — 입금

| 항목 | 내용 |
|---|---|
| 인증 | 없음 (시뮬레이터가 은행 역할) |

**요청** `{ "userId": "u-0001", "amount": 180000 }` (amount 0 이상 정수)
**응답 200** `{ "status": "CONFIRMED" }`
**에러**: 404 RESERVATION_NOT_FOUND, 409 DEPOSIT_NOT_ACCEPTABLE (PENDING_DEPOSIT 아님, 기한 지남, 금액 불일치, 다른 사용자).

---

### 4.9 `POST /reservations/{id}/cancel` — 예매 취소

| 항목 | 내용 |
|---|---|
| 인증 | 없음 |
| 외부 호출 | CARD 예매면 모의 결제사 취소 |

**요청** `{ "userId": "u-0001" }`
**응답 200** `{ "status": "CANCELED" }`

| 현재 상태 | 처리 | 응답 |
|---|---|---|
| CONFIRMED + CARD | 결제사 취소 성공 → 결제 CANCELED, 예약 CANCELED, 좌석 즉시 AVAILABLE | 200 |
| CONFIRMED + CARD | 결제사 취소 실패 → 상태 그대로 | 502 PG_UNAVAILABLE |
| CONFIRMED + DEPOSIT, PENDING_DEPOSIT | 예약 CANCELED, 좌석 즉시 AVAILABLE | 200 |
| 그 외 | - | 409 RESERVATION_NOT_CANCELABLE |

**에러**: 404 RESERVATION_NOT_FOUND, 409, 502.

---

### 4.10 `GET /reservations/{id}` — 예약 조회

| 항목 | 내용 |
|---|---|
| 인증 | 없음 |
| 용도 | 202 이후 결제 결과 폴링, 입금 대기 확인 (사용자 요청으로 집계) |

**응답 200**
```json
{ "reservationId": 812, "seatId": 37, "label": "D7", "grade": "A",
  "seats": [ { "id": 37, "label": "D7", "grade": "A", "price": 90000 }, { "id": 38, "label": "D8", "grade": "A", "price": 90000 } ],
  "userId": "u-0001", "status": "CONFIRMING", "holdExpiresAt": "...", "totalPrice": 180000, "paymentMethod": "CARD",
  "depositDeadline": null, "payment": { "orderId": "6f1c…", "status": "REQUESTED" } }
```
- `seatId`·`label`·`grade`는 대표 좌석(가장 작은 id). 좌석 목록의 기준은 `seats`.
- `payment`는 결제가 없으면 `null`.

**에러**: 404 RESERVATION_NOT_FOUND.

---

### 4.11 판매 종료 이후 동작 (SPEC 6.10)
| 엔드포인트 | `now ≥ saleEndAt` 이후 |
|---|---|
| `GET /seats`, 새 `POST /holds` | 409 SALE_ENDED (스케줄러 전이라도 즉시) |
| 기존 예약의 checkout·deposit·release·confirm, 입금, 취소, 조회 | 계속 처리 |

---

### 4.12 관리 API

#### `POST /admin/reset`
트래픽이 없을 때만 호출한다. 모든 필드 선택, 생략 시 현재 값 유지.
```json
{ "rows": 10, "cols": 10, "grades": [ { "name": "VIP", "rows": 1, "price": 150000 } ],
  "strategy": "conditional", "dbBackstop": true, "timeScale": 1,
  "holdTtlSec": 420, "confirmDeadlineSec": 30, "maxSeatsPerUser": 4,
  "depositDeadlineSec": 60, "returnDelaySec": 30, "saleDurationSec": 1200, "reopenWindowSec": 50,
  "seatsRateLimitEnabled": true, "seatsMinIntervalSec": 1.0, "seatsCacheSec": 0,
  "anchorAt": "...", "saleEndAt": "...", "runEpoch": "...", "closeQueueOnSoldOut": true }
```
| 필드 | 타입 | 규칙 |
|---|---|---|
| strategy | `conditional` \| `pessimistic` \| `optimistic` \| `naive` | |
| timeScale | 1 \| 2 \| 4 | |
| grades | Grade[] | 행 수 합 ≠ rows → 400 |
| seatsCacheSec | 0 \| 1 | |
| anchorAt | Instant | 오면 Clock 기준을 새로 잡음 (SPEC 3장) |
| saleEndAt | Instant | 없으면 `now + saleDurationSec ÷ timeScale` |
| closeQueueOnSoldOut | boolean | 받기만 하고 쓰지 않음 (두 서버에 같은 본문을 보내기 위함) |

- 처리: 테이블 비우기 → 좌석 재생성 → backstop 적용 → 캐시·새로고침 기록·카운터·회수 목록·알림 큐 초기화.
- **응답 200** `{ "config": { ...적용된 RuntimeConfig } }`. 에러 400 VALIDATION_FAILED.

#### `GET /admin/stats`
```json
{ "serverTime": "...", "config": { ... },
  "seats": { "total": 100, "AVAILABLE": 12, "HELD": 30, "PENDING_DEPOSIT": 4, "RETURN_PENDING": 0, "SOLD": 54, "byGrade": { "VIP": { "AVAILABLE": 0, "SOLD": 10 } } },
  "reservations": { "HELD": 0, "CONFIRMING": 0, "CONFIRMED": 0, "EXPIRED": 0, "RELEASED": 0, "PAYMENT_FAILED": 0, "PENDING_DEPOSIT": 0, "DEPOSIT_EXPIRED": 0, "CANCELED": 0 },
  "payments": { "REQUESTED": 0, "APPROVED": 0, "FAILED": 0, "CANCELED": 0 },
  "releaseBatches": { "open": 1, "nextReleaseAt": "...|null", "totalReleased": 3 },
  "counters": { "holdAttempts": 0, "holdSuccess": 0, "holdConflicts": 0, "confirms": 0, "declines": 0, "notPayable": 0,
                "expiredByScheduler": 0, "recoveryAttempts": 0, "recovered": 0,
                "immediateReturns": 0, "userCancels": 0, "depositsRequested": 0, "depositsPaid": 0, "depositsExpired": 0,
                "reopenCount": 0, "reopenSeats": 0, "slotNotifyDropped": 0, "acceptedInvalidKeys": 0 } }
```
- 상태별 수는 DB 집계, counters는 메모리 (reset 시 0).
- 시뮬레이터가 1초마다 읽어 `releaseAt` 판단에 쓴다 (내부 판단용, 사용자 요청 집계에서 제외).

#### `GET /admin/metrics`
1초 창 계측 스냅샷. 형태는 SPEC 8.4. 주요 최상위 필드:

| 필드 | 내용 |
|---|---|
| at, windowMs, simElapsedSec, timeScale, phase | 창 정보 |
| endpoints.{name} | `rps`, `inflight`, `avgMs`, `latency{p50,p95,p99,max}`, `status{코드:수}`, `errors{code:수}` |
| total | 전체 rps, latency, `errorClasses{conflict, notPayable, key, declined, client, rateLimited, shed, server}` |
| cumulative | 엔드포인트별 누적 백분위 |
| pool | `active, idle, pending, max, acquireMs{p95,max}, timeouts` |
| db | `lockWaits, lockWaitMaxMs` |
| seatsCache | `hit, miss, dbReads` (최근 1초) |
| http, jvm, pg, notifier, schedulers | 스레드, 메모리·GC, 결제사 승인 지연, 알림 큐, 스케줄러 마지막 실행 |
| seatMap | 좌석 상태 코드 문자열 (A/H/D/R/S) |
| heldRemainingMs, depositRemainingMs | 좌석별 남은 시간 |
| seatConflicts | 최근 2초 좌석별 409 수 |
| releaseAt, returnPendingSeats, saleEndAt, events | 취소표·판매 종료·최근 1초 사건 |

- 엔드포인트 이름(`endpoints` 키): `seats`, `holds`, `release`, `checkout`, `confirm`, `reservation`, `deposit`, `depositPay`, `cancel`. 다른 이름으로 같은 값을 중복 기록하지 않는다.

#### `GET /admin/metrics/stream`
SSE, 1초마다 `event: metrics`, `data:`는 `GET /admin/metrics`와 같은 본문.

#### `GET /admin/snapshot`
불변식 검사용 전체 덤프.
```json
{ "seats": [ { "id": 1, "label": "A1", "status": "SOLD", "current_reservation_id": 12, ... } ],
  "reservations": [ ... ], "reservation_seats": [ ... ], "payments": [ ... ], "release_batches": [ ... ],
  "counters": { ... }, "runEpoch": "...",
  "droppedNotifications": [ { "kid": "...", "type": "HOLD_CLEARED", "at": "..." } ] }
```
- 테이블 키는 테이블 이름, 행은 **DB 컬럼 이름(snake_case) 그대로**. 시각은 ISO-8601 문자열, UUID는 문자열 (DB_SPEC 3장).
- 테이블 간 시점을 맞추기 위해 REPEATABLE READ 읽기 + 스냅샷 잠금으로 읽는다.

#### `GET /actuator/health`
Spring Actuator 기본. 시뮬레이터 사전 점검(SPEC 10.2)에 쓴다.

---

### 4.13 CORS
`cors-origins`(기본 `http://localhost:8090`)에 대해 `/admin/**` GET을 허용한다. 대기열 서버·모의 결제사도 같다.

### 4.14 예약 서버가 보내는 알림 (→ 대기열 서버 5.4)
| 사건 | 호출 |
|---|---|
| 선점 성공 | `POST /internal/slots/{kid}/events` `HOLD_ACTIVE` |
| 그 사용자의 HELD/CONFIRMING이 모두 끝남 (만료·해제·결제 실패, 스케줄러 포함) | `HOLD_CLEARED` |
| 카드 CONFIRMED (요청 또는 복구 스케줄러), 입금 대기 신청 | `COMPLETED` + kid 회수 |
| soldOut 값이 바뀜, 그리고 5초마다 | `POST /internal/sale-state` |
- 요청 트랜잭션 **커밋 후** 큐에 넣고 비동기 전송. `at`은 사건 시각.
- 실패 시 재시도 3회 (실제 1초·2초·4초), 그래도 실패하면 버리고 `counters.slotNotifyDropped` +1, `droppedNotifications` 기록. 사용자 요청의 성공 여부와 무관.

---

## 5. 대기열 서버 (:8082)

### 5.1 `POST /queue/enter` — 대기 진입
**요청** `{ "userId": "u-0001" }`
**응답 200**
```json
{ "token": "3b0e…", "status": "WAITING", "position": 812, "pollAfterMs": 4000, "reason": null }
```
| 상황 | 응답 |
|---|---|
| 활성 토큰(WAITING/ADMITTED) 있음 | 그 토큰 그대로 |
| 정상 | 새 WAITING, 맨 뒤 |
| 판매 종료 이후 | 200 + `status: CLOSED`, `reason: SALE_ENDED` |
| 매진이고 `closeQueueOnSoldOut = true` | 200 + `status: CLOSED`, `reason: SOLD_OUT` |
- EXPIRED·LEFT였던 사용자가 다시 진입하면 맨 뒤 새 토큰.
- **에러**: 400 VALIDATION_FAILED.

### 5.2 `GET /queue/status?token=` — 순번 조회
| status | 본문 |
|---|---|
| WAITING | `{ "status": "WAITING", "position": 812, "pollAfterMs": 4000 }` |
| ADMITTED | `{ "status": "ADMITTED", "admissionKey": "v1.…", "admissionExpiresAt": "..." }` |
| 그 외 | `{ "status": "CLOSED", "reason": "SOLD_OUT" }` (`reason`: `SALE_ENDED` \| `SOLD_OUT` \| null) |

| position | pollAfterMs |
|---|---|
| ≤ 50 | 1000 |
| ≤ 300 | 2000 |
| ≤ 1000 | 4000 |
| 그 외 | 6000 |
- 순번은 입장 주기마다 미리 계산해 두고 요청 때 읽기만 한다.
- **좌석 상황 필드는 없다** (대기자는 좌석 상황을 모른다).
- **에러**: 400 VALIDATION_FAILED (없는 토큰).

### 5.3 `POST /queue/leave` — 대기 이탈
**요청** `{ "token": "3b0e…" }`
**응답 200** `{ "left": true }`
- WAITING/ADMITTED면 LEFT, 자리 반납. 이미 끝난 토큰이어도 200. `token` 없음 → 400.

### 5.4 `POST /internal/slots/{kid}/events` — 자리 상태 알림 (예약 서버 → 대기열 서버)
| 항목 | 내용 |
|---|---|
| 인증 | `X-Internal-Secret` (틀리면 401) |

**요청** `{ "type": "HOLD_ACTIVE", "at": "2026-10-06T08:34:51.120Z" }`
| type | 처리 |
|---|---|
| HOLD_ACTIVE | busy = true (만료 미룸) |
| HOLD_CLEARED | busy = false (만료 시각이 지났으면 다음 주기 EXPIRED) |
| COMPLETED | 토큰 COMPLETED, 자리 반납 |

**응답 200** `{ "ignored": false }` (반영함) / `{ "ignored": true }` (무시함)
- 무시: 없는 kid, 끝난 자리, 다른 실행의 `X-Run-Epoch`, kid별 마지막 반영 `at`보다 오래된 이벤트, COMPLETED 이후의 모든 이벤트.
- `type` 없음 → 400.

### 5.5 `POST /internal/sale-state` — 판매 상태 (예약 서버 → 대기열 서버)
| 항목 | 내용 |
|---|---|
| 인증 | `X-Internal-Secret` |

**요청** `{ "soldOut": true, "releaseAt": "...|null" }`
**응답 200** `{ "ignored": false }` (다른 실행의 `X-Run-Epoch`면 `true`)
- `closeQueueOnSoldOut = true`이고 soldOut이면 **WAITING만** CLOSED(SOLD_OUT). ADMITTED는 닫지 않는다. soldOut=false가 오면 다시 접수 (SPEC 7.6).

### 5.6 관리 API
| 메서드·경로 | 요청 / 응답 |
|---|---|
| `POST /admin/reset` | `{ "anchorAt", "maxActive", "admitPerSec", "admissionTtlSec", "busyMaxExtraSec", "timeScale", "saleEndAt", "closeQueueOnSoldOut", "runEpoch" }` → 200 `{ "config" }` |
| `GET /admin/stats` | `{ "WAITING", "ADMITTED", "COMPLETED", "LEFT", "EXPIRED", "CLOSED", "busy", "slotsBusyOverTtl", "counters": { "entered", "admitted", "keysIssued", "expired", "expiredByBusyCap", "closedSoldOut", "closedSaleEnded", "slotEvents": { "HOLD_ACTIVE", "HOLD_CLEARED", "COMPLETED", "ignored" } } }` |
| `GET /admin/metrics` | `{ "windowMs", "endpoints": { "queue.status": { "rps", "latency", "status" } }, "cumulative", "waiting", "active", "busy", "slotsBusyOverTtl", "expiredByBusyCap", "admittedThisTick", "keysIssued", "jvm" }` |
| `GET /admin/snapshot` | 토큰 전체, 현재 자리, 입장 기록, `activeMax`, `admittedPerTickMax`, `busyCapExpirations[{ kid, userId, at }]` |
| `GET /actuator/health` | 기본 |

---

## 6. 모의 결제사 (:8081)

### 6.1 `POST /auth` — 카드 인증 (사용자가 직접)
**요청** `{ "orderId": "6f1c…", "amount": 180000 }`
| 응답 | 조건 |
|---|---|
| 200 `{ "paymentKey": "pk_93ab…" }` | 성공, 결제 AUTHORIZED 저장 (출금 없음) |
| 400 AUTH_FAILED | 확률 `authFailureRate` |
- 사람이 인증하는 시간(페르소나별 5~240초)은 시뮬레이터가 호출 전에 기다린다.

### 6.2 `POST /payments/confirm` — 승인 (예약 서버가 호출)
**요청** `{ "paymentKey": "pk_93ab…", "orderId": "6f1c…", "amount": 180000 }`
| 응답 | 조건 |
|---|---|
| 200 `{ "status": "DONE", "approvedAt": "..." }` | 성공. 지연 U(confirmMinMs, confirmMaxMs) |
| 400 INSUFFICIENT_FUNDS | 확률 `declineRate` (DECLINED 저장) |
| 400 INVALID_REQUEST | 키·주문·금액 불일치, 이미 처리됨 |
| 응답 없음 | 확률 `timeoutRate`: `confirmMaxMs + 10000`ms 대기. 내부 승인 여부는 50%로 정해 저장 (결과 불명 재현) |

### 6.3 `GET /payments/{paymentKey}` — 상태 조회
**응답 200** `{ "status": "AUTHORIZED|DONE|DECLINED|CANCELED" }`
**에러**: 404 NOT_FOUND.

### 6.4 `POST /payments/{paymentKey}/cancel` — 취소·망취소
**응답 200** `{ "status": "CANCELED" }`
- DONE/AUTHORIZED면 CANCELED. 이미 CANCELED면 같은 응답 (멱등). DECLINED는 200으로 현재 상태.
- **에러**: 404 NOT_FOUND.

### 6.5 관리 API
| 메서드·경로 | 요청 / 응답 |
|---|---|
| `GET /admin/config`, `PUT /admin/config` | `{ "authFailureRate", "declineRate", "timeoutRate", "confirmMinMs", "confirmMaxMs", "seed" }` |
| `POST /admin/reset` | 본문 선택 `{ "anchorAt" }` → 결제 기록 비움, Clock 기준 설정 → 200 `{ "reset": true }` |
| `GET /admin/stats` | `{ "authRequests", "authFailed", "confirmRequests", "done", "declined", "timedOut", "canceled", "confirmInflight", "confirmLatency": { "p50", "p95", "p99" } }` |
| `GET /admin/payments` | 결제 전체 목록 (스냅샷용) |
| `GET /actuator/health` | 기본 |

---

## 7. 시뮬레이터 (:8090)

| 메서드 · 경로 | 요청 | 응답 | 에러 |
|---|---|---|---|
| `POST /api/runs` | RunConfig (SPEC 10.3) | **202** 현재 실행 상태 (`GET /api/runs/current`와 같은 형태) | 409 RUN_ACTIVE, 409 TARGET_DOWN, 400 INVALID_CONFIG |
| `POST /api/runs/current/stop` | - | 200 현재 실행 상태 (정지 중 `STOPPING` → `STOPPED`). 실행 중이 아니면 그대로 현재 상태 | - |
| `GET /api/runs/current` | - | 현재 상태 + 실시간 집계 | - |
| `GET /api/defaults` | - | 기본 RunConfig | - |
| `GET /api/runs` | - | `{ "runs": [ { runId, label, status, startedAt, durationMs, timeScale, strategy, queueMode, schemaVersion, hasInvariants, invariantsPassed, fingerprint, pinned } ], "warnings": [ "파일명" ] }` | - |
| `GET /api/runs/{id}` | - | run.json + summary.json | 404 |
| `GET /api/runs/{id}/summary` | - | summary.json (SPEC 11.8) | 404 RUN_NOT_FOUND |
| `PATCH /api/runs/{id}` | `{ "label", "notes", "pinned" }` (부분) | 200 run 메타 | 404 |
| `DELETE /api/runs/{id}` | - | 200 `{ "deleted": true }` | 404, 409 RUN_ACTIVE (실행 중 기록) |
| `GET /api/runs/{id}/timeseries?fields=&step=` | `fields`: 쉼표 구분 경로, `step`: 다운샘플 초 | 시계열 행 배열 (SPEC 8.5, 9.4) | 404 |
| `GET /api/runs/{id}/events` | - | 사건 배열 | 404 |
| `GET /api/runs/{id}/invariants` | - | invariants.json (SPEC 8.6) | 404 RUN_NOT_FOUND (외부 검사 안 함) |
| `GET /api/compare?a=&b=` | runId 2개 | SPEC 9.2 | 404 |
| `GET /api/presets` | - | 프리셋 이름 목록 | - |
| `GET /api/presets/{name}`, `PUT /api/presets/{name}` | RunConfig | RunConfig | 404 |
| `GET /api/stream` | - | SSE 1초마다 `event: tick` | - |

### 7.1 RunConfig (요청 본문 `POST /api/runs`)
필드와 기본값은 SPEC 10.3을 따른다. 그룹: 기본(label, notes) / 좌석(rows, cols, grades) / 사용자(users, arrival, personaMix, churnMix, 이탈·대기 이탈·재방문·재시도, ticketCountMix, adjacentRequiredRate, seed) / 대기열(maxActive, admitPerSec, admissionTtlSec, closeQueueOnSoldOut, busyMaxExtraSec) / 예약·결제 / 판매(saleDurationSec) / 서버(strategy, dbBackstop, seatsRateLimitEnabled, seatsMinIntervalSec, seatsCacheSec) / 실행(timeScale, timeLimitSec) / 계측(thresholds, minSamplesPerWindow) / 접속(targets, requestTimeoutMs).

검증: 등급 행 수 합 = rows, 비율 묶음 합 = 100, 범위 최소 ≤ 최대, timeScale ∈ {1, 2, 4}. 실패 시 400 INVALID_CONFIG.

### 7.2 실행 시작 순서 (`POST /api/runs`, SPEC 10.2)
1. 세 서버 `GET /actuator/health` → 하나라도 실패하면 409 TARGET_DOWN, 기록을 만들지 않음.
2. anchor 기준 잡기, `runEpoch`·`saleEndAt` 계산 → 세 서버 `POST /admin/reset` (+ 결제사 `PUT /admin/config`). 서버별 `anchorAt`은 보내기 직전 값. 실패 시 실행 FAILED.
3. 서버별 시각 차이 측정 → run.json `clockOffsetsMs`.
4. run.json 작성, 사용자 시작, 202 응답 (실행은 백그라운드로 계속).

### 7.3 `tick` (SSE `/api/stream`)
형태는 SPEC 11.9. 최상위: `runId, elapsedMs, running, finalizing, simElapsedSec, timeScale, users, outcomes, events, clientRps, pg, recentEvents, signals, server, queueServer, queueStats, mockPg, connections`.
- `signals.levels`: `ok | warn | bad | low` (`low` = 표본 적음, p95·err에만, SPEC 8.1).
- `finalizing`: 판매 종료 후 정리 대기 중이면 true (SPEC 8.5, 14.1).

---

## 8. 대표 흐름 (호출 순서)

### 8.1 카드 예매 성공
```
1  POST queue/queue/enter                       → WAITING, position
2  GET  queue/queue/status?token= (반복)         → ADMITTED, admissionKey
3  GET  server/seats                [Key]       → 좌석 현황
4  POST server/holds                [Key, Idem] → 201 HELD            ⇢ server→queue HOLD_ACTIVE
5  POST server/holds/{id}/checkout  [Key]       → orderId, amount
6  POST mock-pg/auth                            → paymentKey
7  POST server/payments/confirm     [Key]       → 200 CONFIRMED       ⇢ server→mock-pg confirm, server→queue COMPLETED
```

### 8.2 입금 대기 → 미입금 → 취소표
```
4  POST server/holds                → 201 HELD
5  POST server/holds/{id}/deposit   → PENDING_DEPOSIT                  ⇢ COMPLETED (자리 반납)
   (입금 기한 경과)                  → 예약 DEPOSIT_EXPIRED, 좌석 RETURN_PENDING, 배치 편입
   (배치 시각 도달)                  → 좌석 AVAILABLE, REOPEN 사건, GET /seats.releaseAt 갱신
```

### 8.3 경쟁에서 짐
```
4  POST server/holds   → 409 SEAT_UNAVAILABLE { unavailableSeatIds }
3' GET  server/seats   → 다시 조회 후 다른 좌석 선택 (빈 좌석 없으면 새로고침 간격 대기, 이탈 성향 적용)
```

### 8.4 결과 불명
```
7  POST server/payments/confirm → 202 CONFIRMING
8  GET  server/reservations/{id} (폴링) → CONFIRMED 또는 PAYMENT_FAILED (복구 스케줄러가 결론)
```
