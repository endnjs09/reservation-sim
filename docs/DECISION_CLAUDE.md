# 구현 결정 기록

기준: [SPEC.md](SPEC.md), [docs/plan.md](docs/plan.md). 이전 기록은 `decision.md`, `DECISIONS_old.md`.

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 결정 기록 파일은 `DECISION_CLAUDE.md` | 사용자 지시 (2026-10-04) | - | 0장 2번의 `DECISIONS.md` 대신 |
| 구현 계획은 `docs/plan.md`에 새로 작성 | 사용자 지시 (2026-10-04) | - | SPEC.md 통합본 기준으로 다시 쓰기 위해 |
| UI 참고는 `design_ex/` 두 목업 | 사용자 지시 (2026-10-04) | simulator static | 13장의 `docs/ui-*.dc.html` 파일이 없음 |
| 패키지 구조는 실제 코드 기준 | 사용자 지시 (2026-10-04) | 전 모듈 | 2.3·2.4는 예시 |
| ~~커밋은 모든 작업이 끝난 뒤 한 번에~~ → 단계마다 로컬 커밋 | AGENTS.md "단계마다 테스트 통과 → 커밋 → 멈추고 보고"로 대체 (2026-10-04) | - | 사용자가 AGENTS.md 기준으로 진행하라고 지시 |
| UI 목업은 `design_ex/`를 쓴다 | AGENTS.md·SPEC 2.1은 `docs/ui-*.dc.html`을 가리키지만 레포에 없음. 사용자 지시대로 `design_ex/`의 두 목업을 씀 | simulator static (W4·W5) | 같은 목업의 원본 위치 |
| bench 포트: Postgres 127.0.0.1:55440, server 18180, mock-pg 18181, queue 18182, simulator 18190 | 이전 검증 환경의 배정을 그대로 씀 (10.4 "현재 검증 환경 그대로") | `scripts/bench-up.sh`, `scripts/bench-down.sh` | 이전 진단 실행과 같은 조건 유지. 개발용 기본 포트(5432, 8080~8090)와 겹치지 않음 |
| bench DB는 새 컨테이너 `reservation-bench-db`(DB `reservation_bench`, cpuset 6-11, 메모리 2g) | 이전 `reservation-w3-validation-db`에 제한값이 없어서 새로 만듦. 이전 컨테이너는 지우지 않고, 55440을 잡고 있으면 bench-up이 멈춤 | `scripts/bench-up.sh` | 10.4 DB 제한값을 컨테이너 생성 때 고정하기 위해 |
| queue·mock-pg `/admin/stats`에 `serverTime` 추가 | `clockOffsetsMs`(3장)를 재려면 각 서버 시각이 필요한데 두 서버에는 시각 필드가 없음. 필드 추가만 함 (0장 9번) | queue `QueueState.stats`, mock-pg `MockPgService.Stats` | 예약 서버 `serverTime`과 같은 이름으로 맞춤 |
| `SWAP_USED`는 절대 사용량(SwapTotal − SwapFree) > 100MB 기준, 실행당 1회 | 10.4 문구 그대로. 시작 전 사용량은 bench-up이 출력 | simulator 스왑 감시 | 시작 전부터 스왑을 쓰는 환경도 측정 품질 저하로 봄 |
| `earlyQuitRate` 입력은 받아서 버린다 (거부하지 않음) | 10.3 "설정·프리셋·UI에서 제거". 예전 프리셋·기록에 남아 있어도 읽히도록 `JsonCodec.config`·`readSummary`에서 지움. standbyLimit처럼 400으로 거부하지 않음 | `JsonCodec`, `RunConfig`, `VirtualUser`, `presets/default.json` | 사용자 저장 프리셋을 깨지 않기 위해. 난수 1회는 유지하고 제거 전 코드로 뽑은 사용자 2000명 프로필 해시로 같음을 고정 (`W3ProfileStabilityTest`) |
| 예전 기록과의 fingerprint 비교: 저장값이 다르고 둘 중 하나의 config에 `earlyQuitRate`가 있을 때만 다시 계산 | 9.1 제외 목록에 earlyQuitRate 추가. 예전 기록의 저장 fingerprint에는 그 값이 들어 있어 그대로 비교하면 SCENARIO_DIFFERS. 저장 파일은 고치지 않음 | `RunComparison.compare` | 항상 다시 계산하면 저장 fingerprint를 기준으로 한 기존 계약·테스트가 바뀜. 영향 범위를 예전 기록으로 좁힘 |
| `connections` 값에 `ok`·`lastOkAt`을 추가하고 `connected`·`lastSuccessAt`·`url`·`checkedAt`은 유지 | 11.9 형태 `{ ok, lastOkAt, error }` + 0장 9번(필드 추가만) | `ServerMetricsPoller.Connection`, `app.js` | 기존 화면·테스트를 깨지 않고 명세 필드를 제공 |
| `memAvailableMbAtStart`는 `/proc/meminfo` MemAvailable(MB), 못 읽으면 null | 8.5 | `HostMemory`, `RunService.metadata` | 측정값을 지어내지 않음 |
| 서버 Clock은 모듈마다 같은 `AnchoredClock`(server·queue·mock-pg에 각자 복사) | 3장. 모듈끼리 의존하지 않는 기존 방식(`RequestHistograms`)을 따름. reset 전·`anchorAt` 없는 reset은 시스템 시계 | `config/AnchoredClock`, `queue/AnchoredClock`, `mockpg/AnchoredClock`, 각 Clock 빈 | 명세 그대로 |
| reset 수신 시각은 컨트롤러 진입 시점의 `System.nanoTime()` | 본문 파싱 뒤라 수 ms 늦지만 TRUNCATE·좌석 생성 같은 처리 시간은 들어가지 않음. 테스트 MutableClock이면 anchor를 무시 | `AdminController`/`AdminService`, `QueueController`, `MockPgController` | 필터 단계까지 내리면 세 모듈 필터를 모두 고쳐야 함. 측정한 차이(아래)가 500ms 기준보다 충분히 작음 |
| reset 순서를 queue → mock-pg → server → mock-pg config로 바꿈 | 각 서버는 받은 순간을 anchorAt으로 삼아서 앞 reset 처리 시간만큼 뒤 서버 시계가 늦음. 실측 clockOffsetsMs: 이전 순서 server −223 / queue −132 / mockPg −410ms → 바꾼 뒤 server −225 / queue −22 / mockPg −109ms | `RunEngine.prepare`, `S5EngineTest` 가짜 전송의 호출 순서 | mock-pg가 500ms 경계에 가까웠음. 명세 10.2는 순서를 정하지 않음 |
| `clockOffsetsMs` = 서버 `serverTime` − (anchorAt + 요청 왕복 중간의 단조 경과), ms. 못 읽으면 null. \|차이\| > 500ms면 `CLOCK_OFFSET_HIGH` 사건(`target`, `offsetMs`) | 3장. 실행은 계속 | `RunService.recordClock`, `RunStore.clock` | 명세 그대로 |
| run.json `startedAt`은 기준점을 잡은 뒤 `anchorAt`으로 덮어씀 | 기록은 엔진 생성 때 먼저 만들어지고, 기준점은 실행 시작(run) 때 잡힘. 3장 "기준점 하나" | `RunStore.clock` | `startedAt`·`anchorAt`·`t`가 같은 순간을 가리키도록 |
| snapshot `takenAt`도 anchor 시계 | 3장. 서버들의 시각(anchor 기준)과 같은 축 | `RunService.anchoredNow` | 같은 실행 기록 안에서 시각 기준을 섞지 않음 |
| ~~METRICS_LOST 5초 판단은 poller의 시스템 시각으로 둠~~ → (W4) poller `checkedAt`을 실행 anchor 시계(`RunService.clockNow`)로 바꿈 | W4 확인 중 4× 실행이 METRICS_LOST로 한 번 실패. 3장(시간 판단에 벽시계 금지)에 맞추고, 계측 실패 시작을 로그로 남김. 처음엔 "프로세스 시작 시각 + 단조 경과"로 했으나, 실행 기록의 `preparedAt`(anchor 기준)과 출발점이 달라 실행 초반 계측 샘플이 버려지는 문제를 확인(1× 실행 초반 수 초 동안 카드·시계열 비어 있음) → 같은 anchor 시계로 통일. 수정 후 1.3초부터 계측 표시 | `ServerMetricsPoller.fetch`, `RunService.clockNow`, `RunService.connection` | 판단 규칙(5초)과 W2LifecycleTest 경계는 그대로 |
| 4× 기본 실행의 대기열 서버 연결 폭주(accept 대기열 넘침)는 고치지 않고 보고 | `ListenOverflows 507`, `queue.enter` 연결 timeout 147건으로 FAILED. 서버 설정 변경은 측정 환경에 영향 → AGENTS.md 확인 대상 | - | 1× 기준선에는 영향 없음 |
| `ENV_DIFFERS`의 bench 비교는 설정값(`ports`·`heap`·`cpus`·`db`)만, 관측값 `verified`·`mismatch`는 제외 | 10.4 "값(포트, 힙, CPU 배치)이 다른 두 실행" | `RunComparison.bench` | 실제 힙 보고값의 미세한 차이로 경고가 나지 않게 |
| 비교 응답에 `warningNotes` 추가 (`CLOCK_MODEL_DIFFERS` 문구) | 9.2 정해진 문구를 담을 곳이 없어 필드 추가 (0장 9번). 판정 문장의 "(주의: …)"는 코드 목록 그대로 | `RunComparison.notes` | W5 화면이 문구를 그대로 보여줄 수 있게 |
| `ENV_DEGRADED`는 비교할 때 두 실행의 events.ndjson에서 `SWAP_USED`를 찾음 | 9.2. events 파일이 없는 예전 기록은 false | `RunStore.hasEvent`, `SimulatorController.compare` | 명세 그대로 |
| bench 프로필의 실제 대조: 예약 서버 `/admin/metrics` `jvm.heapMaxMb`, 시뮬레이터 `Runtime.maxMemory()`. -Xmx와 5% 넘게 다르면 `mismatch`에 이름 | 10.4 "값을 기록". 프로필만 믿지 않음 | `BenchProfile.verify` | JVM 보고값이 -Xmx보다 조금 작을 수 있음 |
| HTTP 클라이언트 4곳에서 `HttpRequest.timeout`을 쓰지 않고 `sendAsync(...).get(timeout)` + `cancel`로만 시간 제한 | W3 bench 짧은 실행 5번 모두 사용자 오류(클라이언트 timeout 1~6건, 서버는 0.5초 안에 응답). HttpClient 로그에서 timeout 난 요청이 **보낸 그 밀리초에** JDK `ResponseTimerEvent`로 취소됨을 확인. 끝난 이전 요청의 응답 타이머가 남아 같은 연결을 재사용한 다음 요청을 끊는 JDK 21.0.12 HttpClient 경합. 재현 테스트(`W3StaleTimerTest`, 64스레드 × 짧은 timeout 요청 뒤 5초 timeout 요청): 수정 전 3번 중 2번 실패(`request timed out` 57~64건, `ConnectionExpired` 7건), 수정 후 5번 모두 통과 | 시뮬레이터 `JdkHttpTransport`, `ServerMetricsPoller`, 예약 서버 `PgClient`, `SlotNotifier` | timeout 판단 기준(8.3 timeout = 응답 시간 초과)은 그대로이고 잘못된 timeout만 없앰. 중간에 시도한 Tomcat `max-keep-alive-requests` 변경은 원인이 아니어서 되돌림 |
| `bench-up.sh`에 진단용 `SIM_EXTRA_OPTS`, `SERVERS_EXTRA_ARGS` 환경 변수 | 측정 환경 값은 그대로이고 진단 JVM 옵션만 추가 가능. 기준선 실행에는 쓰지 않음 | `scripts/bench-up.sh` | HttpClient timeout 원인 진단에 사용 |
| `logs/`는 .gitignore | 10.4 로그·pid·bench.json은 실행할 때마다 생기는 파일 | `.gitignore` | |
| queue `QueueConfig`, mock-pg `Stats` 레코드에 구성요소 추가(`anchorAt`, `serverTime`) | 3장·clockOffsetsMs. queue는 보조 생성자를 두면 Jackson이 anchorAt을 무시해서 테스트 호출부를 고침. mock-pg `Stats` 동등 비교 테스트는 `serverTime`만 빼고 비교 | `QueueConfig`, `QueueState`, `MockPgService.Stats`, 관련 테스트 | 기대값은 그대로 |

## 2.3·2.4 예시와 실제 위치 대응표

| 명세 예시 | 실제 |
|---|---|
| `config/RuntimeConfig` | `config/RuntimeConfig`, `config/RuntimeConfigStore` |
| `config/ClockConfig` | `config/ClockConfig` (mock-pg `PgClockConfig`, queue `QueueApplication#clock`) |
| `seat/SeatSummary` | `snapshot/AvailabilitySummary` (+ `seat/SeatController`의 `/seats` 요약 필드) |
| `deposit/ReopenScheduler` | `resale/ReopenScheduler`, `resale/ReleaseBatchRepository` |
| `reservation/ReservationController` | 조회 `payment/PaymentController#GET /reservations/{id}`, 취소 `payment/CancellationController` |
| `admission/RevokedKeys` | `admission/KeyRegistry` |
| `sale/SaleClock` | `sale/SaleService`, `sale/SaleEndScheduler` |
| `metrics/MetricsStreamController` | `metrics/MetricsController` (`/admin/metrics`, `/admin/metrics/stream`) |
| `admin/SnapshotService` | `snapshot/StateSnapshotReader`, `snapshot/SnapshotLocks` |
| simulator `engine/Churn` | `engine/ChurnPolicy` |
| simulator `engine/ClientMetrics` | `engine/RequestHistograms`, `engine/RunStats` |
| simulator `service/CompareService` | `service/RunComparison` |
| simulator `api/*Controller`, `StreamController` | `service/SimulatorController`, `service/StreamService` (별도 `api/` 패키지 없음) |

## W4 실시간 화면 결정 (2026-10-04)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 실시간 화면 정적 파일 3개를 새로 씀 | 기존 v0.4 화면 대신 design_ex 실시간 목업 배치·값(1920×1080, 카드 4·노드 5·좌석 맵·하단 띠·설정 패널 360px)을 따름. 외부 라이브러리·글꼴 요청 없음 | `static/index.html`, `app.css`, `app.js` | 13·14.1장 |
| 설정 패널은 탭마다 접이식 그룹을 하나만 펼침 (`details name`) | 13장 "내부 스크롤 없음"과 10.3 RunConfig 전 항목 입력을 함께 지키기 위해. 관람객 속도는 빠름/일반/느림 선택 단추로 한 그룹 안에서 바꿈. 범위 값(최소~최대)은 한 줄에 두 칸 | `app.js` `groups`, `renderFields` | 1080px 안에 모든 항목 접근 |
| 등급은 설정의 등급 목록을 그대로 쓰고 추가·삭제 단추는 두지 않음 | 등급 이름은 VIP·S·A·B 네 가지로 고정(서버 검증). 행 수를 바꾸면 뒤 등급부터 맞춤(기존 동작) | `app.js` | 그룹 높이를 줄이기 위해 |
| 실시간 이벤트 상세는 한 쪽 20건, 최근 80건을 4쪽으로 넘김 | 13장 "내부 스크롤 없음" | `renderDetail` log | 860×760 창 안에 표시 |
| 이벤트 분류: 만료·반환·취소표·예매 취소·미입금 → 반환 / 예매 성공·결제·입금·카드·인증 → 결제 / 충돌·선점 → 선점 / 그 외 시스템 | 시뮬레이터가 남기는 문구 기준 | `categorize` | 14.1 필터 5종 |
| 이벤트 시각은 시뮬레이션 시각으로 표시 | 시뮬레이터 `recentEvents`는 실제 경과(mm:ss.t)라 배속을 곱함 | `collectEvents` | 헤더 시각과 같은 기준 |
| 사용자 노드: 입장 = 좌석 조회·선점·인증·승인 중, 이탈 = gaveUp + soldOut + abandoned, 예매 완료 = confirmed + depositPaid | tick의 users·outcomes에서 계산 | `render` | 14.1 "도착 전 / 입장 / 이탈 / 예매 완료" |
| 누적 카운터는 tick 값으로: 반환 = 사용자가 본 즉시 반환 + 예매 취소, 취소표 오픈 = reopenSeen, 충돌 = conflicts, Hardcore 잔류 = 끝나지 않은 hardcore 사용자 | 예약 서버 counters는 tick에 없음 | `counterValues` | 서버 요청을 늘리지 않음 |
| 요청 점: 초당 생성 수 = rps × 0.35, 속도 380px/s, 선마다 동시 40개 | 14.1 "점 수는 rps 비례, 선마다 최대 40개". `prefers-reduced-motion`이면 그리지 않음 | `frame`, `dotRate` | |
| 대기열 노드 배지는 phase로 (정상·취켓팅·매진·취소표 오픈·종료) | 목업 | `render` | |
| ~~결제사 노드 p95는 1초 창 값, 없으면 결제사 누적 값~~ → 1초 창 값만, 그 초에 승인 요청이 없으면 "–" | 사용자 지시 (8.4 "요청 0인 창의 백분위는 null" 원칙). 상세 창은 "최근 1초"와 "누적"을 따로 표시. 확인: 실행 중 tick 40개 대조, 요청 없는 초 19개 모두 "–", 있는 초 21개 모두 값, 불일치 0 (`run-f3648f3e`) | `render`, `renderDetail` pg | 누적 값을 그 초 값처럼 보이지 않게 |
| 취소표 카운트다운은 시뮬레이션 시간으로 0.25초마다 줄임 | `releaseAt − server.at`(실제 ms) × 배속 | `paintCountdown` | 헤더 시각 기준과 맞춤 |
| 실행 결과 탭은 W5 전까지 "준비 중"과 선택한 실행 ID만 표시 | 가짜 결과를 넣지 않음 | `results-view` | |
| 화면 확인 도구 `scripts/ui-check.mjs` | Windows Chrome headless를 CDP로 조작해 스크린샷·클릭·콘솔 오류·외부 요청 확인 | `scripts/ui-check.mjs` | W4·W5 화면 검증 |

## 발견: 4× 도착 폭주 때 연결 대기 줄 넘침 (2026-10-04)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| **발견**: Tomcat 연결 대기 줄(accept-count) 기본 100에서 4× 기본 실행의 도착 폭주(1,200명이 실제 1.25초 안)에 커널이 연결을 버림 | `run-9fb0999c`: `TcpExtListenOverflows` 507, `queue.enter` 연결 timeout(1초) 147건 → FAILED(USER_ERRORS). 재현 `run-493139a5`: 첫 1초에 81건 추가로 버려짐. 1× 기준선에서는 발생 안 함 | - | 나중에 accept-count 100 대 1024를 실험 변수로 비교 (사용자 지시) |
| METRICS_LOST(`run-5fcd1e4f`)는 같은 순간의 연결 폭주에 계측이 휩쓸린 것 | 그 실행은 실제 2~3초에 `queue.enter` timeout 277건, 시계열은 실제 3.2초(t=12)에서 끊김(대기열 계측 0), 10.1초에 METRICS_LOST. 재현 실행에서는 계측이 이미 열린 연결을 재사용해 버팀 → 타이밍에 따라 걸림 | - | 계측과 서비스가 같은 연결 줄·스레드를 씀 |
| 대기열 서버·예약 서버 `server.tomcat.accept-count: 1024` | 사용자 지시. 커널 `net.core.somaxconn` 4096(상한 아님) 확인. mock-pg는 100 유지 | `server`·`queue` `application.yml` | 4× 도착 폭주 수용 |
| `environment.bench.acceptCount`에 **실제** 대기 줄 크기 기록 | bench-up이 서버 기동 후 `ss -ltn`의 Send-Q(커널에 걸린 backlog)를 읽어 bench.json에 씀 (`server`, `queue`, `mockPg`, `somaxconn`). 그래서 bench.json을 시뮬레이터 기동 직전에 씀 | `scripts/bench-up.sh` | 설정값이 아니라 커널이 실제로 쓰는 값 |
| `ENV_DIFFERS` bench 비교 항목에 `acceptCount` 추가 | 10.4 "bench 값이 다른 두 실행을 비교하면 ENV_DIFFERS" | `RunComparison.bench` | 100 대 1024 비교 시 경고 |

## W5 실행 결과 화면 결정 (2026-10-04)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 결과 화면 로직은 별도 파일 `results.js` (app.js의 공용 함수·상세 창 사용) | design_ex 결과 목업 배치(요약 카드 6, 2×2 그래프 1300px, 오른쪽 표·불변식·조건) | `static/results.js`, `index.html`, `app.css` | app.js가 커지지 않게 |
| 그래프는 SVG로 직접 그림, 시계열은 `step = ceil(판매 길이 ÷ 300)` | 9.4 다운샘플 규칙. 서버 aggregate가 백분위는 최대, 나머지는 평균 | `drawChart`, `load` | 외부 라이브러리 금지(13장) |
| 에러율 그래프의 "conflict 비중" = 시계열 `server.errorClasses.conflict ÷ signals.rps × 100` | 시계열 한 줄에 둘 다 1초 창 값 | `conflictPct` | 14.2 "전체 + conflict 비중 연하게" |
| 단계 음영은 A 실행의 시계열 phase로, B의 단계 경계는 가로축 아래 눈금 | 9.4 | `phaseSegments` | |
| 비교 표는 `/api/compare`의 metrics(9.3 순서·winner·diffPct)를 그대로 표시, 단일 보기는 같은 14지표를 그 실행 값으로 | 9.2·9.3 | `renderTable` | 판정 문장도 API 값 |
| 실행 관리(이름·메모·고정·삭제·요약 JSON)는 마지막으로 고른 실행(비교면 B)에 적용 | 14.2 "실행 관리" | `renderManage` | 공간 제약 |
| 모든 실행 서랍은 상세 창 안에서 검색 + 한 쪽 12건 넘김 | 13장 내부 스크롤 없음 | `r-all` | |
| 상세 창 공용화: app.js `openModal(title, sub, build)` (tick마다 다시 그리지 않음) | 결과 화면의 그래프 상세·불변식·편집·삭제 확인·서랍이 같은 860×760 창을 씀 | `app.js openModal` | 입력 중인 값 유지 |
| 목록 경고에 깨진 기록의 디렉터리 이름 표시 (`run-xxxx/run.json`) | 8.5 "읽을 수 없는 파일은 warnings에 파일명". 이전엔 "run.json"만 | `RunStore.list` | 테스트 `brokenRunJsonIsNamedInWarnings` |

## reset 시각 보정 (2026-10-05)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| reset마다 서버별 `anchorAt` = 기준 시각 + 보내기 직전까지의 단조 경과 (anchor 시계의 "지금"). `saleEndAt`·`startedAt`·`t`는 여전히 기준 시각 하나에서 계산 | 사용자 지시. 3장 "세 서버에 같은 anchorAt"에서 바뀜 (명세 반영은 사용자 몫). 서버는 받은 순간을 이 값으로 삼으므로 앞 reset들의 처리 시간이 뒤 서버 차이로 쌓이지 않음 | `RunEngine.prepare`, `W3AnchorEngineTest` (보낸 순서대로 증가, 예전처럼 같은 값을 보내면 실패) | clockOffsetsMs 축소 |
| **실측 (ms, server / queue / mockPg)** — 같은 bench, 새로 띄운 직후 1회(cold) + 이어서 2회(warm), 1× 짧은 실행 | 보정 전: cold −137 / −22 / −99 (`run-1b042faf`), warm −21 / −5 / −13 (`run-26795a70`), −15 / −3 / −9 (`run-886786fc`). 보정 후: cold −33 / −9 / −90 (`run-9b23f849`), warm −6 / −4 / −2 (`run-05a3a1e1`), −5 / −3 / −2 (`run-272e6db9`) | - | warm은 모두 6ms 이하. cold의 mockPg −90은 보내는 쪽 순서가 아니라 받는 쪽 첫 요청 처리(본문 해석·초기화) 시간이라 이 보정으로 줄지 않음 |

## 화면 맞춤: 전체화면 아래 잘림 (2026-10-05)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| ~~(아래 "창에 맞춰 늘어나는 배치"로 대체)~~ 1920×1080 stage의 축소 비율 = min(창 너비/1920, 창 높이/1080). 남는 가로 여백은 가운데 정렬(배경색 `--bg`) | 사용자 보고(`design_ex/1.png`, `2.png`: 전체화면 1920×896에서 사용자·결제사 카드와 모달 아래 잘림). 명세 13장 "가로 기준 비율 축소"와 다름 (명세 반영은 사용자 몫) | `app.js` `fit()` | 가로 기준만 쓰면 16:9보다 세로가 짧은 창에서 stage 높이가 창을 넘고 `overflow:hidden`으로 잘림. 확인(ui-check): 1920×896·1920×1080·1440×860·2560×1080·1280×1000 모두 stage 하단 ≤ 창 높이, 문서 높이 = 창 높이 |

## 화면 맞춤: 창에 맞춰 늘어나는 배치 (2026-10-05)

명세 13장 "1920×1080 기준, 비율 유지 축소 (`transform: scale`, 가로 기준)"와 다른 결정이다 (사용자 지시, 명세는 사용자가 고친다). 고정 크기 축소는 창 비율이 16:9가 아니면(브라우저 주소창·북마크바 때문에 거의 항상) 양옆이나 아래에 여백이 남는다 (`design_ex/3.png`).

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 화면 전체 = 창 크기(100vw × 100vh), flex 배치. 고정: 헤더, 지표 카드 높이, 하단 띠, 설정 패널 폭 360px, 결과 화면 오른쪽 열 폭 558px. 늘어남: 구조도·DB 줄, 결과 그래프 영역 | 사용자 지시 | `index.html`, `app.css` (`.stage`, `.main`, `.viz`, `.panel`, `.r-charts`, `.r-side`) | 여백 없이 창을 채움 |
| 구조도와 DB를 분리. 구조도는 노드·선 좌표(700×670) 그대로, 비율 = min(줄 높이 ÷ 670, (줄 폭 − DB 최소 폭) ÷ 700), 폭 = 700 × 비율, 세로 가운데. DB가 남은 폭 전부 | 사용자 승인 "구조도 폭 = 650 × 높이 ÷ 670"에서 650 → 700으로 바꿈: 좌표 650~700 사이에 "예약 → DB" 선과 이름표가 있어서 이를 포함해야 선이 DB 테두리에 닿는다. 폭 조건은 DB 최소 폭을 지키려는 것 | `app.js` `layout`/`layoutStructure`, `.structure-inner` | 구조도 비율 유지 + 선이 DB와 이어짐 |
| 구조도 최소 비율 0.8. 그보다 작아져야 하는 창은 화면 전체 축소. 기준 최소 창 = 폭 360 + 48 + 700×0.8 + DB 최소 폭 760, 높이 = 고정 높이(실측) + 670×0.8. 축소할 때도 stage 논리 크기를 창 비율에 맞춰(창 ÷ 축소 비율) 여백이 생기지 않게 함 | 사용자 지시(최소 비율 0.8). DB 최소 폭 760은 범례 한 줄(약 700px)과 좌석 이름표 폭에서 정함 | `app.js` `layout` | 작은 창에서 글자가 너무 작아지지 않게 |
| "읽는 법" 줄은 구조도 밖, 구조도·DB 줄 아래 고정 줄로 옮김 | 예전 좌표(구조도 안 top 690)는 폭이 700보다 넓어 구조도 비율에 묶을 수 없음 | `index.html` `.howto` | - |
| 좌석 칸 크기를 DB 영역 크기로 계산(상한 68×38 → 96×52), 남는 폭·높이는 가운데 정렬. 영역 크기가 바뀌면 다시 그림 | 제안대로 진행(사용자 승인) | `app.js` `buildSeatMap` (배치 키에 영역 크기 포함) | 스크롤 없음 유지 + 큰 창에서 빈 공간 줄임 |
| 요청 점 canvas 해상도 = 700×670 × 구조도 비율 × 전체 축소 × devicePixelRatio | 제안대로 | `app.js` `layoutStructure`, `frame` | 확대해도 흐려지지 않게 |
| 상세 창은 가운데 정렬, 크기 min(860×760, 화면 − 48px) | 제안대로 | `app.css` `.modal` | 위치 고정 좌표 제거 |
| 결과 그래프 4개는 창 크기가 바뀌면 마지막 데이터로 다시 그림 | 제안대로 | `results.js` `relayout` | viewBox = 실제 크기 |
| 세로 스크롤 허용은 설정 패널의 설정 영역(`.cfg-form`)과 결과 화면 오른쪽 열(`.r-side`)뿐 | 사용자 지시 | `app.css` | 메인 시각화는 스크롤·잘림 없음 |
| 확인 (ui-check, 실제 저장 기록 GET만 프록시): 1920×1080, 1920×910(브라우저 최대화·주소창 포함), 2560×1440, 1440×860, 1280×720 | 모두 문서 크기 = 창 크기, 여백 0, 하단 띠 아래 끝 ≤ 창 높이, 범례 한 줄. 구조도 비율 1.07 / 0.86 / 1.65 / 0.8(전체 축소) / 0.8(전체 축소). 결과 화면 오른쪽 열 스크롤 생김: 1920×910 136px, 1440×860 38px, 1280×720 96px | 스크린샷 `build/reports/layout/` | - |

## 사용자 노드 합 불일치 (2026-10-05)

발견: 2× 실행 `run-28f5ea69` t=484(08:04, 매진 중) 화면 대기 932 + 입장 198 + 이탈 5 + 예매 완료 59 = 1,194. timeseries `users` 덤프: arriving 0 · waiting 932 · admitted_browsing 198 · pending_deposit 1 · **departed 808** · cancel_wait 2 · done 59 = 2,000. 빠진 약 800명은 `departed`(11.5 취소표 재방문 대기)이고, 입금 대기(pending_deposit)·예매 후 취소 대기(cancel_wait)도 어느 칸에도 없었다. 예매 완료는 `confirmed + depositPaid`로 입금 예매를 두 번 셌다(입금 완료 사용자는 결과가 confirmed이고 depositPaid는 그 위의 표시). casual 이탈 5명: casual은 11.4대로 떠나지만 11.5에 따라 결과 확정이 아니라 재방문 대기(departed)로 가고, 판매 종료 때 gaveUp으로 확정된다. 이 실행은 12:20에 중지되어 casual 1,329명이 incomplete. 대기 중 사용자에게는 이탈을 적용하지 않음(11.4)도 맞다.

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 시뮬레이터가 사용자마다 정확히 한 칸으로 센 `userGroups`를 tick에 추가: arriving / waiting / inside(admitted_browsing·holding·authenticating·confirming·pending_deposit) / revisitWait(departed) / left(결과 gaveUp·soldOut·abandoned) / bought(결과 confirmed + cancel_wait) / stopped(결과 incomplete·error). 결과가 이미 있으면 상태보다 결과 기준 | 사용자 지시 "합이 항상 users". 화면 계산을 시뮬레이터 한 곳에 두어 Java 테스트로 불변 조건 검사 | `RunStats.userGroup`/`userGroups`, `Live.userGroups`, `StreamService` tick, `RunService.completedLive` | 화면 칸 합 = users |
| 사용자 노드 칸 6개(3×2): 도착 전 · 대기 · 입장 · 재방문 대기 · 이탈 · 예매 완료. 중단(stopped)은 0보다 클 때만 머리글에 "· 중단 n" | 명세 14.1 표 "도착 전/입장/이탈/예매 완료"에 대기·재방문 대기를 더함(사용자 지시 "빠진 상태를 칸으로 추가") | `index.html`, `app.css .node-users`(top 490, 높이 180), `app.js userCells`, `EDGES[0]` 끝점 y 520 → 490 | 칸 하나에 의미가 다른 상태를 섞지 않음 |
| 사용자 상세 창: 같은 6칸 + 중단 안내 + "재방문 대기 성향" 막대(usersByChurn.departed). 입장 중 성향에 pending_deposit 포함. 누적 카운터 상세의 예매 완료도 userGroups.bought | 같은 정의로 통일 | `app.js` 상세 창 | casual 이탈이 재방문 대기로 가는 것을 화면에서 확인 가능 |
| 예매 후 취소 대기(cancel_wait)는 예매 완료로 셈 | 이미 확정(카드)·입금 완료한 사용자. 결과는 취소 시도 후 confirmed로 확정 | `RunStats.userGroup` | - |
| 테스트 `UserGroupsTest`: 고치기 전 `userGroups` 없음으로 컴파일 실패 확인. 고친 뒤 통과. departed를 빼는 변형으로 실패 확인 후 되돌림 | AGENTS.md 테스트 규칙 | `simulator/src/test/.../UserGroupsTest.java` | - |

## 실행 중지 때 예매 후 취소 대기(cancel_wait) 사용자 (2026-10-05)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| cancel_wait 상태에서 incomplete로 끝나는 사용자(실행 중지·시간 제한)는 결과 confirmed. 실행되지 않은 취소 예정은 `summary.events.cancelPendingAtStop` +1 (11.8 추가만) | 사용자 지시. `RunStats.finish` 한 곳에서 처리: 사용자 스레드 인터럽트 경로와 `finishPending` 경로 모두 | `RunStats.finish`, `EVENTS` | 실제 예매가 끝난 사람. 서버 CONFIRMED와 맞춤 |
| 기준선 영향 없음 | 저장된 COMPLETED 실행 22개 모두 outcomes.incomplete = 0 → 바뀌는 경우가 없음. 끝까지 간 실행 테스트에서 cancelPendingAtStop = 0 | `V5EngineTest` | - |
| 기존 기록으로 등식 확인: `run-28f5ea69`(중지) 서버 snapshot CONFIRMED 58 = summary confirmed 56 + 중지 때 cancel_wait 2. 새 규칙이면 58 = 58 | snapshot.json reservations 집계 | - | 새 코드로 중지 실행 확인은 bench 확인 실행에서 |
| 테스트: 고치기 전 5개 실패(confirmed 0·incomplete 1, events 키 없음) 확인, 고친 뒤 통과, 특례만 끈 변형에서 3개 실패 확인 후 되돌림 | AGENTS.md | `V5EngineTest.stopWhileWaitingToCancelKeepsTheConfirmedPurchase` 등 | - |

## 대기 이탈 (2026-10-05, 사용자 지시 · 명세 반영은 사용자 몫)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 판단 시점: 순번 조회(GET /queue/status) 응답이 WAITING일 때마다. 처음 들어간 응답(POST /queue/enter)에서는 판단하지 않고 대기 시작 기록만. Δt 첫 값 = 대기 시작부터 | 지시 "순번 조회를 받을 때마다" | `VirtualUser.admit`, `QueueAbandon` | - |
| 줄 멈춤 기준 순번 = (지금 − 창 길이) 이전의 마지막 기록. 대기열에 다시 들어가면(EXPIRED·LEFT 재진입, 재방문) 기록·Δt 기준·대기 시작을 새로 잡음 | 다시 들어가면 맨 뒤 순번이라 이전 기록과 비교하면 거꾸로 감 | `QueueAbandon.start` | - |
| 난수: 사용자마다 별도 스트림 `SplittableRandom(SplittableRandom(seed ^ 상수).nextLong() + index)`. hardcore·꺼짐은 난수를 쓰지 않음 | 지시 "별도 스트림". 켜고 꺼도 다른 판단(도착·성향·좌석·재방문)의 난수 순서가 같음 | `QueueAbandon.stream`/`forUser` | 같은 seed 비교 가능 |
| 시뮬레이션 시간 = (nanoTime − 첫 대기 시작) × 배속 | nanoTime 절댓값에 배속을 곱하면 넘칠 수 있음 | `QueueAbandon.sim` | - |
| 대기 이탈 후: POST /queue/leave → 재방문 대기(departed, 11.5 그대로). 판매 종료까지 다시 오지 않으면 결과 gaveUp (스스로 떠남, 매진 퇴장 soldOut과 구분) | 지시는 "재방문 대기로" 까지. 최종 결과는 11.4 입장 후 이탈과 같은 gaveUp | `VirtualUser.run` | - |
| 집계: outcomes·outcomesByChurn `queueAbandoned` = 대기 중 이탈을 한 번이라도 한 **사용자 수**(다른 outcomes처럼 사용자 단위, milestone). 횟수는 events `queueAbandons`, 그중 줄 멈춤 상태 `queueAbandonsStalled` | 지시는 outcomes에 "횟수", events에 "건수". 같은 값을 두 번 두지 않고 outcomes는 사용자 수로 통일 (한 사람이 재방문 후 다시 떠나면 횟수만 늘어남) | `RunStats.Milestone`, `EVENTS` | outcomes 의미 일관 |
| 설정: `queueAbandonEnabled`(새 실행 기본 true), `queueHalfLifeSec {casual 180, persistent 600}`, `queueStallWindowSec 60`, `queueStallMinProgress 0.05`. RunConfig 필드라 fingerprint에 자동 포함 | 지시 | `RunConfig`, `app.js` 시나리오 탭 > 이탈 성향 | - |
| **값이 없는 기록은 꺼짐**으로 읽음. API·프리셋은 기본값과 합쳐 켬 | 구버전 summary는 RunConfig로 다시 읽혀 저장값처럼 보인다(`JsonCodec.readSummary`). 없을 때 켬이면 이 규칙 전 실행이 켬으로 표시되어 기록이 틀어짐 | `RunConfig` 생성자 | 기록 보존 |
| 테스트 "줄 멈춤 90초에 약 50%": 멈춤 판단은 대기 60초 뒤부터라 대기 시작 기준 90초 누적은 1 − 0.5^(60/180 + 30/90) ≈ 37%. 그래서 "60초에 남은 사람 중 다음 90초 안에 떠나는 비율 ≈ 50%"(반감기 90)와 "60초까지 ≈ 20.6%"(반감기 180)로 확인. 허용 오차 ±0.03(4,000명, 약 3.8σ) | 지시 두 문장(60초 전 멈춤 판단 없음, 90초에 50%)을 둘 다 맞게 해석 | `QueueAbandonTest` | - |
| 테스트: 고치기 전 컴파일 실패(QueueAbandon 없음) 확인. 변형으로 확인: 멈춤 절반 제거 → 멈춤 테스트 실패, forUser가 사용자 본래 seed 사용 → 별도 스트림 테스트 실패, 꺼짐 무시 → 꺼짐 테스트 2개 실패. 모두 되돌림 | AGENTS.md | - | - |
| **bench 확인 (1×, 2026-10-05)** 다른 docker 컨테이너 2개가 떠 있었으나 CPU 0.2%·0.02%. 스왑 0, SWAP_USED·CLOCK_OFFSET_HIGH 없음 | - | - | - |
| 새 기준선: conditional `run-535adb85`, pessimistic `run-f7d2b182` (pinned). 둘 다 COMPLETED·quiesced, 판매 종료 t=1201/1202, incomplete·error 0, clockOffsetsMs −4~0 / −3~−1ms. 비교: 경고 없음, configDiff `server.strategy` 하나 | 지시 | - | - |
| 이전 기준선 `run-25757891`·`run-2abb5695`는 "진단 · 대기 이탈 없음 · … (이전 bench 기준선)"으로 이름 변경, 고정 유지 (이전 교체 때와 같은 방식) | 지시 + 이전 관례 | - | 새 기준선과 비교하면 SCENARIO_DIFFERS (queueAbandon 필드) |
| 켬(`run-535adb85`) 대 끔(`run-af45174f`, 같은 코드·조건부): 경고 SCENARIO_DIFFERS 하나, configDiff `queueAbandonEnabled` 하나. 대기열 서버 대기 인원 t=120/300/600/900/1100: 켬 997/574/157/37/1 · 끔 1,335/1,160/709/229/82. 매진 뒤 평균: 대기 320 대 687, 대기열 상태 조회 rps 139.5 대 252.2(−45%), 예약 서버 rps 115.9 대 116.1, p95 4.5 대 4.4ms. 폭주 구간 p95 9.1 대 6.2ms, 최대 p95 40.1 대 27.2ms(한 번의 튐), 에러율 36.2 대 35.9%, 409 충돌 909 대 1,249, 판매 100 = 100, 매진 t=17 = 17. 대기 이탈 920명(955회: casual 784·persistent 136·hardcore 0), 줄 멈춤 상태 이탈 0회, 재방문 79 대 27 | 지시 3번 | `build/analyze.mjs` 결과 | 대기 인원·폴링 rps는 같이 줄고 예약 서버 처리량·p95는 사실상 같음(입장 정원 200이 그대로 차 있음) |
| 중지 실행 등식(새 코드): `run-526f4d91`(취소율 0.5, 7분 뒤 중지) summary confirmed 63 − canceledAfterPurchase 9 = 54 = 서버 CONFIRMED 54. cancelPendingAtStop 23 (예전 규칙이면 incomplete) | 앞 절 지시 | - | 등식 성립 |
| 줄 멈춤 규칙은 기본 실행에서 0회 발동해도 그대로 둠. 매진 뒤에도 입장자가 빠지고 앞사람이 떠나 순번이 60초에 5% 넘게 줄어드는 것이 실제 대기열과 같은 움직임. 정원이 hardcore로 막혀 줄이 실제로 멈추는 시나리오용 안전장치 | 사용자 결정 (2026-10-05) | - | 기준을 바꿔 발동시킬 근거 없음 |

## 매진 시 대기열 닫기 기본 켬 · 좌석 조회 새로고침 제한 · 좌석 조회 캐시 (2026-10-05, 사용자 지시 · SPEC 반영 전)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| closeQueueOnSoldOut 새 실행 기본 true: RunConfig.defaults, presets/default.json, 화면(기본값에서 옴). 서버·대기열 application.yml 기본도 true로 맞춤. 저장 기록은 기록 값 그대로(구버전 summary는 기존대로 없으면 false) | 지시 + 서버 단독 기동 기본값 일치 | `RunConfig`, `presets/default.json`, `server`·`queue` `application.yml`, `V4ChurnPolicyTest`(기본값 기대를 새 지시대로 변경) | - |
| 새로고침 제한: 입장키(kid)별 마지막 **허용** 시각만 메모리(ConcurrentHashMap)에. 429는 기다리는 시간을 늘리지 않음. retryAfterMs = 남은 실제 ms(올림), Retry-After = 초 올림. 시각은 서버 Clock(AnchoredClock) | 지시. 429마다 시각을 갱신하면 계속 두드리는 사용자가 영원히 막힘 | `seat/SeatsGate.admit`, `SeatController` | - |
| 순서: 입장키 검사(메모리: 서명·runEpoch·회수·만료) → 새로고침 제한 → 판매 종료 확인(메모리) → 캐시 → DB(기존 advisory lock 포함 `queue.check` + 스냅샷). 429와 캐시 hit은 DB를 쓰지 않음 | 지시 "입장키 → 제한 → 캐시 → DB" | `SeatController.seats` | - |
| 캐시: 응답 전체를 runEpoch와 함께 저장. 만료 또는 다른 runEpoch면 DB. 동시에 만료되면 한 요청만 DB(synchronized), 나머지는 그 결과. reset 때 비움 | reset 직전 응답이 다음 실행에 섞이지 않게 | `SeatsGate.read` | - |
| reset 본문에 없는 seats 값은 직전 값을 이어받음 (다른 reset 필드와 같은 규칙). 시뮬레이터는 세 값을 늘 보냄 | 기존 ResetRequest 규칙 | `ResetRequest` | - |
| /admin/metrics `seatsCache`: 누적 hits·misses·dbReads·rateLimited + 이번 창 초당 값(`…Rps`) + 설정(cacheSec, rateLimitEnabled, minIntervalSec). 구조도 예약 서버→DB 선의 좌석 조회 몫은 `dbReadsRps`(없던 서버면 seats rps) | 지시 "실제 DB 조회 수 기준" | `MetricsService.seatsCache`, `app.js` | - |
| 에러 분류 `rateLimited`(429 + RATE_LIMITED) 추가, 그 외 429는 기존 shed. **에러율(errPct)·실패율(failPct)은 429를 분자·분모 모두에서 뺌** | 지시는 "에러율에 넣지 않음". 분모에 남기면 새로고침이 늘수록 에러율이 낮아 보여 이전 기준선과 비교가 틀어짐 | `RequestHistograms`(서버·시뮬레이터), `RunMeasurements`, `RunStats.RequestMeasurement` | 비교 일관 |
| 집계: summary.events.rateLimited(사용자가 받은 429 수), summary.errorClasses.rateLimited, timeseries `signals.rateLimitedRps`·`server.seatsCache`·`client.rateLimited` | 지시 | `RunStats`, `RunMeasurements` | - |
| 시뮬레이터: 429면 retryAfterMs(실제 ms)만큼 쉬고 다시 조회. 이탈 성향 시계는 그대로 | 지시 | `VirtualUser.browse` | - |
| 시뮬레이터 설정 seatsRateLimitEnabled·seatsMinIntervalSec·seatsCacheSec: 새 실행 기본 true·1.0·0, **값이 없는 저장 기록은 false·1.0·0**(둘 다 없던 실행). 화면: 고급 탭 서버 전략 그룹(동시성 전략·DB 방어선 아래) | 대기 이탈 때와 같은 기록 보존 규칙 | `RunConfig`, `app.js` | - |
| run.json server.* 에 seatsRateLimitEnabled·seatsMinIntervalSec·seatsCacheSec를 reset 뒤 서버 /admin/stats config에서 읽어 기록 | 지시 | `RunService.prepared` | - |
| 계약 `contracts/seats-rate-limited.json`. 서버 테스트가 실제 429 본문과 필드를 비교, 시뮬레이터 가짜 응답은 이 파일에서 만듦 | AGENTS.md 테스트 규칙 | `SeatsContract.rateLimited` | - |
| 테스트: 고치기 전 서버 5개 실패·시뮬레이터 컴파일 실패 확인. 변형 확인: 제한 끔·캐시 무시 → 서버 5개 실패, 429 처리·errPct 제외·failPct 제외를 각각 뺌 → 시뮬레이터 3개 실패. 모두 되돌림 | AGENTS.md | `SeatsRefreshIntegrationTest`, `SeatsRateLimitEngineTest`, `RateLimitedMeasurementsTest` | - |
| **bench 확인 (1×, 2026-10-05)** 다른 docker 컨테이너 3개(개발용 DB 포함, CPU 0~0.24%), 스왑 0, SWAP_USED·CLOCK_OFFSET_HIGH 없음 | - | - | - |
| 새 기준선: conditional `run-ebf26b93`, pessimistic `run-3b71ffc8` (pinned). 둘 다 COMPLETED·quiesced, incomplete·error 0, 판매 100. 비교 경고 없음, configDiff `server.strategy` 하나. 이전 기준선 `run-535adb85`·`run-f7d2b182`는 "구 기본값 · …"으로 이름 변경, 고정 유지 | 지시 5번 | - | - |
| **발견: `run-ebf26b93`에 SALE_ENDED 사건이 없음.** 사용자가 모두 t=1200.9 전에 끝나 엔진이 멈추고, 서버 phase=ENDED를 담은 다음 1초 표본이 찍히기 전에 실행 시계열이 끝남(마지막 표본 t=1200 SOLD_OUT). durationMs 1,200,863로 판매 시간은 정상. 이전 실행 `run-f635ce4e`(4×)도 같은 이유로 ENDED 없음. 사건 기록 시점의 경쟁이라 측정값에는 영향 없음 → 기준선으로 씀. 고치려면 엔진 종료 때 서버 phase를 한 번 더 읽어 SALE_ENDED를 남기는 방법 (보고만) | 측정값 영향 없음, 기록 표시만 빠짐 | `RunMeasurements`, `RunService` | - |
| 캐시 1초 비교 `run-05ca5bed`(같은 설정, seatsCacheSec만 1): 판매 99. 끝에 A2 하나가 AVAILABLE(t≈953 선점 만료로 즉시 반환된 뒤 아무도 사지 않음, 남은 사람은 대부분 2매 이상·연석). CONFIRMED 좌석 99 = 서로 다른 좌석 99 = SOLD 99, 중복 판매 없음 | snapshot 집계 | - | - |

## 실행 종료 정리 · 판매 종료 기록 · 누적 카운터 · 실시간 이벤트 (2026-10-05, 사용자 지시)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 엔진 종료 뒤 대기: 매초 /admin/stats의 HELD·CONFIRMING·PENDING_DEPOSIT이 모두 0이면 바로 snapshot 단계로. 최대 holdTtl+confirmDeadline+5초(지금 값) 그대로. 읽지 못하면 계속 기다림 | 지시. 참고: 엔진 자체가 이미 같은 정리(+ 열린 반환 묶음)를 기다린 뒤 끝나므로 보통은 첫 확인에서 바로 넘어감 | `RunService.execute`·`settled` | 고정 455초 제거 |
| 이 구간 status는 RUNNING 그대로, tick에 `finalizing: true` 추가(추가만). 화면: 상태 "정리 중", 숫자 카드 4개 "–" | 지시 "기존 기록 형식은 바꾸지 않음" → run.json·Current 레코드는 그대로, SSE tick만 필드 추가 | `RunService.finalizing`, `StreamService`, `app.js` | - |
| 엔진이 끝나면 서버·결제사·대기열 지표를 한 번 더 읽어 표본 하나를 남김. 사건(SALE_ENDED 등)은 늘 기록, 시계열 줄은 직전 표본과 다른 초일 때만(같은 t 두 줄 방지) | 지시 | `RunService.finalSample` | 사용자가 1초 표본보다 먼저 끝나도 SALE_ENDED 남음 |
| 누적 카운터 띠·상세에서 "Hardcore"(현재 잔류 인원) 제거. 성향 구성은 사용자 노드 상세의 막대에서만 | 지시 | `index.html`, `app.js` | 누적이 아닌 값이 누적 칸에 있었음 |
| 실시간 이벤트: 시뮬레이터가 마지막 80건(`eventHistory`, 오래된 것 → 최신)을 1초 창과 따로 보관해 tick에 실음. 화면은 실행 id가 있으면(끝난 뒤에도) 이 목록으로 채우고, 새 실행 시작(id 변경)에만 비움 | 화면 목록이 브라우저 메모리에만 있고 1초 창(recentEvents)으로만 채워져, 새로 열거나 재연결하면 끝난 실행의 목록이 비어 있었음 | `RunStats.history`, `Live.eventHistory`, `app.js collectEvents` | - |
| 같은 tick 안 여러 사건이 오래된 것부터 위에 쌓이던 순서를 바로잡음(최신이 맨 위). 중복 제거 키를 원래 줄로 통일 | 발견해서 같이 고침 | `app.js collectEvents` | - |
| 테스트: 1·2번 고치기 전(finalizing 자리만 둔 상태) 3개 실패 확인. 변형(settled 무시·finalSample 제거) → 3개 실패, 이벤트 보관 제거 → 실패 확인 후 되돌림 | AGENTS.md | `FinalizeTest`, `EventHistoryTest` | - |

## 완료 상태 카드 · 그래프 구간 최대 · 엔드포인트 이름 · reservation 집계 (2026-10-06, 사용자 지시)

| 결정한 것 | 어떻게 정했나 | 영향받는 코드 | 이유 |
|---|---|---|---|
| 완료·중지·실패 상태에서도 실시간 화면 서버 지표 카드 4개를 "–"로 비우고 색은 기본, 아래 줄 "실행 끝 · 결과는 실행 결과 탭에서" | 지시. 정리 중과 같은 처리 | `app.js render` | 마지막 1초(판매 종료 409 등)가 빨갛게 남지 않게 |
| 결과 그래프: 9.4 "N초 평균"은 그대로 두고, step>1이면 시계열 응답 signals에 `rpsMax`·`errPctMax`·`poolPctMax`(구간 최대) 추가. 그래프는 평균선 + 연한 점선(구간 최대) + 최고점 표시를 구간 최대로. p95·p99는 원래 구간 최대(9.4) | 평균을 최대로 바꾸면 9.4와 다름. 원인: run-706852b5 step=4 평균에 1초 85%가 묻혀 18%로 보임 | `RunStore.series`, `results.js` | 명세 유지 + 최대 보존 |
| 요약 signals에 `poolPendingAtMax`(최대 풀 사용률을 찍은 초의 풀 대기) 추가, 표의 "최대 풀 사용률" 옆에 "· 대기 n". 이전 기록에는 없어 표시 안 함 | 지시 | `RunMeasurements`, `results.js renderTable` | 순간값인지 포화인지 |
| 예약 서버 지표 키는 8.2대로 `depositPay`·`cancel` 하나씩. 예전처럼 `deposit.pay`·`reservation.cancel`로 기록하고 같은 값을 별칭으로 복사하던 것 제거. 시뮬레이터(클라이언트) 쪽 이름 `deposit.pay`·`reservation.cancel`은 그대로(요청 이름이고 명세에 따로 없음) | 지시 "이름 하나로" + 8.2 | `RequestMetricsFilter`, `MetricsCollector` | - |
| 예전 기록 읽기 유지: 에러율 계산은 두 이름이 다 있으면 depositPay·cancel만 셈, 한쪽만 있으면 그것을 셈. 결과 화면 상세는 depositPay가 없으면 deposit.pay를 읽음 | 기록 형식 추가만 | `RunMeasurements.sample`, `results.js openChartDetail` | - |
| 테스트: 2·4번 고치기 전 4개 실패 확인. 변형(구간 최대 제거·풀 대기 제거·예전 중복 규칙·서버 예전 이름) → 실패 확인 후 되돌림. 1번은 화면 로직이라 ui-check로 같은 가짜 tick의 전·후(완료·중지에서 숫자 21/100 빨강 → "–")를 확인 | AGENTS.md (화면 JS 테스트 틀 없음) | `ChartPeaksAndNamesTest`, `EndpointNamesTest` | - |
| **보고: reservation 집계 불일치는 집계 오류가 아님.** 서버의 `reservation` 246건은 시뮬레이터가 입금 대기 중인 사용자 대신 예약 상태를 1초마다 확인한 내부 조회(`RunHttp.internalReservation`, `VirtualUser.deposit` 202행): 미입금으로 입금 기한(60초)을 기다린 사용자 4명 × 약 60회 ≈ 246. 시뮬레이터는 설계상 내부 조회를 사용자 요청에서 빼고(`requests.byEndpoint`=0), 서버는 받은 요청을 모두 셈 | 원인 먼저 보고 (수정 안 함) | - | 고칠지는 사용자 결정 |
| 입금 대기 중 예약 상태 확인(GET /reservations/{id})을 사용자 요청으로 셈: requests.byEndpoint.reservation·clientLatencyMs.reservation에 들어가 서버 reservation 집계와 같은 기준. 내부 전용 `RunHttp.internalReservation` 제거. 서버는 그대로, 예전 기록은 0 그대로. 테스트 `noPaySendsNoBank…`의 "reservation 0" 기대를 새 지시대로 바꾸고(고치기 전 실패 확인) 가짜 서버의 사용자 요청 수에도 이 조회를 포함 | 사용자 결정 (2026-10-06, 첫 번째 방법) | `VirtualUser.deposit`, `RunHttp`, `V5EngineTest` | 측정 영향 초당 약 0.2건이라 기준선 유지 |
