package dev.endnjs.simulator.engine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import static dev.endnjs.simulator.engine.HttpTransport.Target.*;
import static dev.endnjs.simulator.engine.RunStats.Outcome.*;
import static dev.endnjs.simulator.engine.RunStats.UserState.*;

public final class VirtualUser implements Runnable {
    record Profile(Persona persona,long arrivalMillis,ChurnPolicy.Type churn,int ticketCount,
            boolean adjacentRequired,boolean deposit,boolean payDeposit,boolean abandon,boolean cancelPurchase,
            double depositFraction,double cancelFraction,SplittableRandom random) {}
    static Profile profile(RunConfig config,int index) {
        var random=new SplittableRandom(config.seed()+index);
        long arrival=config.sampleArrivalMillis(random);Persona persona=Persona.sample(config.personaMix(),random);
        random.nextDouble(); // 10.3: draw of the removed earlyQuitRate, kept so a seed yields the same users
        var churn=ChurnPolicy.sample(config,random);
        int ticketCount=sampleTickets(config,random);
        return new Profile(persona,arrival,churn,ticketCount,random.nextDouble()<config.adjacentRequiredRate(),
                random.nextInt(100)>=config.paymentMix().get("card"),random.nextDouble()>=config.depositNoPayRate(),
                random.nextDouble()<config.abandonRate(),random.nextDouble()<config.cancelAfterPurchaseRate(),
                random.nextDouble(.1,.9),random.nextDouble(0,.8),random);
    }
    private static int sampleTickets(RunConfig config,SplittableRandom random) {
        int draw=random.nextInt(100);
        for(int count=1;count<=4;count++) { draw-=config.ticketCountMix().get(Integer.toString(count));if(draw<0) return count; }
        throw new IllegalStateException("Invalid ticket mix");
    }
    private final int index;
    private final String userId;
    private final Profile profile;
    private final RunConfig config;
    private final RunHttp http;
    private final RunControl control;
    private final RunStats stats;
    private final RunTime time;
    private final RevisitSchedule revisits;
    private final ChurnPolicy churn;
    private final SeatPicker picker=new SeatPicker();
    private static final String QUEUE_ABANDONED="QUEUE_ABANDONED";
    private final QueueAbandon queueAbandon;
    private final RevisitRetry revisitRetry;
    private boolean retrying; // 재방문 재시도로 대기열에 다시 들어간 중
    private final java.util.Set<Instant> consideredBatches=new java.util.HashSet<>();
    private String token,admissionKey,closedReason="";
    private boolean saleEnded,purchaseStarted;
    private int conflicts;
    VirtualUser(int index,Profile profile,RunConfig config,RunHttp http,RunControl control,RunStats stats,RunTime time,RevisitSchedule revisits) {
        this.index=index;this.profile=profile;this.config=config;this.http=http;this.control=control;this.stats=stats;
        this.time=time;this.revisits=revisits;this.churn=new ChurnPolicy(profile.churn(),config,profile.random());
        this.queueAbandon=QueueAbandon.forUser(config,index,profile.churn());
        this.revisitRetry=RevisitRetry.forUser(config,index,profile.churn(),profile.persona());
        userId="u-%04d".formatted(index+1);
    }
    @Override public void run() {
        try {
            control.arrive(config.realMillis(profile.arrivalMillis()));
            while(true) {
                if(!admit()) {
                    if(closedReason.equals("SOLD_OUT") && awaitRevisit()) continue;
                    // 대기 이탈: 11.5 재방문 대기. 판매 종료까지 다시 오지 않으면 스스로 떠난 것이라 gaveUp
                    if(closedReason.equals(QUEUE_ABANDONED)) { if(awaitRevisit()) continue;stats.finish(index,gaveUp);return; }
                    stats.finish(index,soldOut);return;
                }
                var outcome=browse();
                if(outcome!=null) {
                    if(!saleEnded && !purchaseStarted && outcome==gaveUp && awaitRevisit()) continue;
                    stats.finish(index,outcome);return;
                }
                stats.event("requeues",userId,"재진입");
            }
        } catch(InterruptedException stopped) { stats.finish(index,incomplete); }
        catch(IOException | RuntimeException failure) {
            stats.note(userId,"오류: "+failure.getMessage());stats.finish(index,control.stopped() ? incomplete : error);
        }
    }
    private boolean admit() throws IOException,InterruptedException {
        stats.state(index,waiting);
        var response=enter();
        if(saleEnded) return false;
        token=response.text("token");queueAbandon.start(time.nanoTime(),position(response));
        boolean polled=false; // 대기 이탈은 순번 조회(GET /queue/status) 응답에서만 판단
        while(true) {
            switch(response.text("status")) {
                case "CLOSED" -> { closedReason=response.body().get("reason") instanceof String reason ? reason : "";return false; }
                case "ADMITTED" -> {
                    if(!response.body().containsKey("admissionKey")) { response=http.send("queue.status",QUEUE,"GET","/queue/status?token="+token,Map.of(),null);requireOk(response);continue; }
                    admissionKey=response.text("admissionKey");stats.state(index,admitted_browsing);
                    if(retrying) { stats.count("revisitRetryAdmitted");retrying=false; }
                    return true; }
                case "WAITING" -> {
                    if(polled && queueAbandon.leave(time.nanoTime(),position(response))) {
                        leave();closedReason=QUEUE_ABANDONED;stats.milestone(index,RunStats.Milestone.queueAbandoned);
                        stats.event("queueAbandons",userId,"대기 중 이탈 · 순번 "+position(response));
                        if(queueAbandon.stalled()) stats.event("queueAbandonsStalled",userId,"줄 멈춤 상태에서 대기 이탈");
                        return false;
                    }
                    long poll=response.number("pollAfterMs");if(poll<=0) throw new IllegalStateException("Invalid poll interval");
                    control.pause(poll);response=http.send("queue.status",QUEUE,"GET","/queue/status?token="+token,Map.of(),null);requireOk(response);
                    polled=true;
                }
                case "EXPIRED","LEFT" -> {
                    stats.event("requeues",userId,"대기열 만료 후 재진입");response=enter();if(saleEnded) return false;token=response.text("token");
                    queueAbandon.start(time.nanoTime(),position(response));polled=false;
                }
                default -> throw new IllegalStateException("Unexpected queue status");
            }
        }
    }
    private HttpTransport.Response enter() throws IOException,InterruptedException {
        var response=http.send("queue.enter",QUEUE,"POST","/queue/enter",Map.of(),Map.of("userId",userId));
        if(ended(response)) { saleEnded=true;closedReason="SALE_ENDED";return response; }
        requireOk(response);return response;
    }
    /** null requests a fresh admission; a successful purchase includes its bank/cancellation follow-up. */
    private RunStats.Outcome browse() throws IOException,InterruptedException {
        var timing=config.personas().get(profile.persona().key());
        while(true) {
            control.check();stats.state(index,admitted_browsing);
            HttpTransport.Response response;
            try { response=server("seats","GET","/seats",null); }
            catch(IOException noResponse) { if(revisits.saleClosed()) return saleEndFallback();throw noResponse; }
            // 8.1-6: the server's SALE_ENDED or phase=ENDED decides; the shared saleEndAt only covers a missing answer.
            if(ended(response) || response.ok() && "ENDED".equals(response.body().get("phase"))) {
                saleEnded=true;leave();return soldOut;
            }
            if(response.status()>=500 && revisits.saleClosed()) return saleEndFallback();
            if(notAdmitted(response)) { churn.reset();return null; }
            // 새로고침 제한: retryAfterMs(실제 ms)만큼 기다렸다 다시 조회. 이탈 성향 시계(잔여석 없음 지속 시간)는 그대로 흐른다
            if(response.status()==429 && response.code().equals("RATE_LIMITED")) { control.pause(Math.max(1,response.number("retryAfterMs")));continue; }
            requireOk(response);var map=seats(response);
            boolean empty=map.stream().noneMatch(seat -> seat.status().equals("AVAILABLE"));
            if(empty && churn.leave(time.nanoTime())) { leave();return gaveUp; }
            if(!empty) churn.reset();
            var selected=picker.pickGroup(map,(int)response.number("cols"),profile.ticketCount(),profile.adjacentRequired(),profile.random());
            if(selected.isEmpty()) {
                stats.event("refreshes",userId,"좌석 새로고침");stats.refreshing(index,true);
                try { control.pause(Math.max(1,config.realMillis(timing.refreshMillis()))); }
                finally { stats.refreshing(index,false); }
                continue;
            }
            String labels=String.join(",",selected.stream().map(SeatPicker.Seat::label).toList());
            control.pause(config.realMillis(timing.selectSec().sampleMillis(profile.random())));
            var held=http.send("holds",SERVER,"POST","/holds",Map.of("X-Admission-Key",admissionKey,"Idempotency-Key",UUID.randomUUID().toString()),
                    Map.of("userId",userId,"seatIds",selected.stream().map(SeatPicker.Seat::id).toList()));
            if(ended(held)) { saleEnded=true;return soldOut; }
            if(notAdmitted(held)) { churn.reset();return null; }
            if(held.status()==409 && held.code().equals("SEAT_UNAVAILABLE")) {
                conflicts++;stats.event("conflicts",userId,labels+" 충돌 ×"+conflicts);continue;
            }
            if(held.status()==409 && held.code().equals("USER_ALREADY_HOLDING")) { control.pause(Math.max(1,config.realMillis(timing.refreshMillis())));continue; }
            requireOk(held);long reservation=held.number("reservationId");
            stats.state(index,holding);stats.note(userId,labels+" 선점 성공");
            if(profile.abandon()) { leave();return abandoned; }
            control.pause(config.realMillis(config.priceStepSec().get(profile.persona().key()).sampleMillis(profile.random())));
            if(profile.deposit()) {
                var outcome=deposit(reservation);if(outcome!=null) return outcome;continue;
            }
            var order=server("checkout","POST","/holds/"+reservation+"/checkout",Map.of("userId",userId));
            if(notAdmitted(order)) return null;
            if(notPayable(order)) { expired();continue; }requireOk(order);
            stats.state(index,authenticating);control.pause(config.realMillis(timing.authSec().sampleMillis(profile.random())));
            var auth=http.send("auth",PG,"POST","/auth",Map.of(),Map.of("orderId",order.text("orderId"),"amount",order.number("amount")));
            if(auth.status()==400 && auth.code().equals("AUTH_FAILED")) {
                stats.event("authFailed",userId,"카드 인증 실패");
                var released=server("release","POST","/holds/"+reservation+"/release",Map.of("userId",userId));
                if(notAdmitted(released)) return null;requireOk(released);continue;
            }
            requireOk(auth);stats.state(index,confirming);
            var result=server("confirm","POST","/payments/confirm",Map.of("userId",userId,"orderId",order.text("orderId"),
                    "paymentKey",auth.text("paymentKey"),"amount",order.number("amount")));
            if(notAdmitted(result)) return null;
            if(notPayable(result)) { expired();continue; }
            if(result.status()==402) { declined();continue; }requireOk(result);
            String status=result.text("status");
            while(status.equals("CONFIRMING")) {
                control.pause(1000);var poll=http.send("reservation",SERVER,"GET","/reservations/"+reservation,Map.of(),null);
                requireOk(poll);status=poll.text("status");
            }
            switch(status) {
                case "CONFIRMED" -> { cancelIfPlanned(reservation);stats.note(userId,labels+" 예매 성공");return confirmed; }
                case "PAYMENT_FAILED" -> declined();
                case "EXPIRED" -> expired();
                default -> throw new IllegalStateException("Unexpected payment result: "+status);
            }
        }
    }
    private RunStats.Outcome deposit(long reservation) throws IOException,InterruptedException {
        var response=server("deposit","POST","/holds/"+reservation+"/deposit",Map.of("userId",userId));
        if(notAdmitted(response)) return null;
        if(notPayable(response)) { expired();return null; }
        requireOk(response);purchaseStarted=true;stats.state(index,pending_deposit);stats.note(userId,"입금 대기 · "+profile.ticketCount()+"매");
        if(profile.payDeposit()) {
            Instant deadline=Instant.parse(response.text("depositDeadline"));
            long remaining=config.realDuration(config.depositDeadlineSec()).toMillis();
            pauseUntil(deadline.minusMillis(Math.round(remaining*(1-profile.depositFraction()))));
            var paid=http.send("deposit.pay",SERVER,"POST","/deposits/"+reservation+"/pay",Map.of(),Map.of("userId",userId,"amount",response.number("amount")));
            if(paid.ok()) { stats.milestone(index,RunStats.Milestone.depositPaid);cancelIfPlanned(reservation);return confirmed; }
            if(paid.status()!=409 || !paid.code().equals("DEPOSIT_NOT_ACCEPTABLE")) requireOk(paid);
        }
        while(true) {
            // 입금 기한까지 예약 상태 확인: 사용자 요청으로 센다 (서버 reservation 집계와 같은 기준, docs/DECISION_CLAUDE.md)
            var observed=http.send("reservation",SERVER,"GET","/reservations/"+reservation,Map.of(),null);requireOk(observed);
            switch(observed.text("status")) {
                case "DEPOSIT_EXPIRED" -> { stats.milestone(index,RunStats.Milestone.depositExpired);stats.note(userId,"미입금 · 예약 마감");return gaveUp; }
                case "CONFIRMED" -> { stats.milestone(index,RunStats.Milestone.depositPaid);cancelIfPlanned(reservation);return confirmed; }
                case "CANCELED" -> { return gaveUp; }
                case "PENDING_DEPOSIT" -> control.pause(1000);
                default -> throw new IllegalStateException("Unexpected deposit state");
            }
        }
    }
    private void cancelIfPlanned(long reservation) throws IOException,InterruptedException {
        if(!profile.cancelPurchase()) return;
        var end=revisits.signal().saleEndAt();long remaining=Math.max(0,Duration.between(time.instant(),end).toMillis());
        if(remaining==0) return;
        stats.state(index,cancel_wait);pauseUntil(time.instant().plusMillis(Math.round(remaining*profile.cancelFraction())));
        while(true) {
            var response=http.send("reservation.cancel",SERVER,"POST","/reservations/"+reservation+"/cancel",Map.of(),Map.of("userId",userId));
            if(response.ok()) { stats.milestone(index,RunStats.Milestone.canceledAfterPurchase);stats.note(userId,"예매 취소 · 즉시 반환");return; }
            if(response.status()==502 && time.instant().isBefore(end)) { control.pause(1000);continue; }
            stats.note(userId,"예매 취소 미완료 · 구매 유지 (HTTP "+response.status()+")");return;
        }
    }
    private boolean awaitRevisit() throws InterruptedException {
        // 재시도가 막혀 돌아온 것이면(매진 중 CLOSED) 이벤트를 다시 남기지 않는다
        if(!retrying) stats.note(userId,"좌석 없이 퇴장 · 취소표 재방문 대기");
        stats.state(index,departed);retrying=false;
        // 재방문 대기 중 재시도: 취소표 오픈과 별개로 다음 간격에 대기열 진입을 다시 시도 (hardcore·persistent)
        long lastAttempt=time.nanoTime(),nextRetry=revisitRetry.active() ? lastAttempt+config.realMillis(revisitRetry.nextIntervalSimMillis())*1_000_000 : Long.MAX_VALUE;
        while(true) {
            control.check();var signal=revisits.signal();if(!time.instant().isBefore(signal.saleEndAt())) return false;
            var release=signal.releaseAt();
            long now=time.nanoTime();
            if(nextRetry!=Long.MAX_VALUE && now>=nextRetry && (release==null || consideredBatches.contains(release))) {
                double elapsedSim=(now-lastAttempt)/1e9*config.timeScale();lastAttempt=now;
                if(!revisitRetry.quitsBeforeAttempt(elapsedSim)) {
                    stats.count("revisitRetries");retrying=true;churn.reset();
                    if(release!=null) consideredBatches.add(release); // 줄에 들어간 뒤 그 취소표 오픈으로 revisitProb를 다시 굴리지 않음
                    return true;
                }
                nextRetry=Long.MAX_VALUE; // persistent가 그만둠: 이제 취소표 오픈 때만
            }
            if(release!=null && consideredBatches.add(release) && profile.random().nextDouble()<config.revisitProb().get(churn.type().name())) {
                Instant target=release.plusMillis(config.realMillis(Math.round(profile.random().nextDouble(-5,5)*1000)));
                boolean valid=true;
                while(time.instant().isBefore(target)) {
                    var latest=revisits.signal();
                    if(!time.instant().isBefore(latest.saleEndAt())) return false;
                    if(!revisits.visitable(release)) { valid=false;break; }
                    control.pause(Math.min(1000,Math.max(1,Duration.between(time.instant(),target).toMillis())));
                }
                if(!time.instant().isBefore(revisits.signal().saleEndAt())) return false;
                if(!valid) continue;
                churn.reset();stats.milestone(index,RunStats.Milestone.revisited);stats.event("revisits",userId,"취소표 재방문");
                stats.event("requeues",userId,"재진입");return true;
            }
            control.pause(nextRetry==Long.MAX_VALUE ? 1000 : Math.min(1000,Math.max(1,(nextRetry-time.nanoTime())/1_000_000)));
        }
    }
    private void pauseUntil(Instant target) throws InterruptedException {
        while(time.instant().isBefore(target)) control.pause(Math.min(1000,Math.max(1,Duration.between(time.instant(),target).toMillis())));
    }
    private HttpTransport.Response server(String endpoint,String method,String path,Map<String,Object> body) throws IOException,InterruptedException {
        return http.send(endpoint,SERVER,method,path,Map.of("X-Admission-Key",admissionKey),body);
    }
    private static long position(HttpTransport.Response r) { return r.body().get("position") instanceof Number n ? n.longValue() : 0; }
    private static boolean ended(HttpTransport.Response r) { return r.status()==409 && r.code().equals("SALE_ENDED"); }
    private boolean notAdmitted(HttpTransport.Response r) throws InterruptedException {
        if(r.status()!=403 || !java.util.Set.of("KEY_INVALID","KEY_EXPIRED","KEY_REVOKED").contains(r.code())) return false;
        leave();return true;
    }
    private static boolean notPayable(HttpTransport.Response r) { return r.status()==409 && r.code().equals("RESERVATION_NOT_PAYABLE"); }
    private static void requireOk(HttpTransport.Response r) { if(!r.ok()) throw new IllegalStateException("HTTP "+r.status()+" "+r.code()); }
    private void expired() { stats.event("holdExpired",userId,"선점 만료"); }
    private void declined() { stats.event("declined",userId,"결제 실패"); }
    private RunStats.Outcome saleEndFallback() throws InterruptedException {
        stats.event("saleEndFallback",userId,"판매 종료 · 서버 응답 없음, 공통 saleEndAt으로 퇴장");
        saleEnded=true;leave();return soldOut;
    }
    private void leave() throws InterruptedException {
        try { http.send("queue.leave",QUEUE,"POST","/queue/leave",Map.of(),Map.of("token",token)); }
        catch(IOException failure) { stats.note(userId,"대기열 퇴장 통신 실패"); }
    }
    private static List<SeatPicker.Seat> seats(HttpTransport.Response response) {
        if(!(response.body().get("seats") instanceof List<?> list)) throw new IllegalStateException("Missing seats");
        return list.stream().map(value -> {
            if(!(value instanceof Map<?,?> seat) || !(seat.get("id") instanceof Number id) || !(seat.get("label") instanceof String label)
                    || !(seat.get("status") instanceof String status) || id.longValue()<1) throw new IllegalStateException("Invalid seat");
            return new SeatPicker.Seat(id.longValue(),label,status);
        }).toList();
    }
}
