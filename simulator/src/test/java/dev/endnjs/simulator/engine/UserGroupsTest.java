package dev.endnjs.simulator.engine;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static dev.endnjs.simulator.engine.RunStats.UserState.*;
import static org.assertj.core.api.Assertions.*;

/** 실시간 화면 사용자 노드: 칸의 합은 항상 users (run-28f5ea69 t=484에서 약 800명이 어느 칸에도 없던 문제). */
class UserGroupsTest {
    private static long sum(Map<String,Long> groups) { return groups.values().stream().mapToLong(Long::longValue).sum(); }

    @Test void everyUserIsInExactlyOneGroup() {
        var config=new JsonCodec().config("{\"users\":12}");
        var stats=new RunStats(config,RunTime.system(),Collections.nCopies(12,Persona.FAST));
        // 0: arriving (기본값)
        stats.state(1,waiting);
        stats.state(2,admitted_browsing);stats.state(3,holding);stats.state(4,authenticating);stats.state(5,confirming);stats.state(6,pending_deposit);
        stats.state(7,departed);                                               // 구매 못 하고 떠나 취소표 재방문 대기 (11.5)
        stats.state(8,cancel_wait);stats.milestone(8,RunStats.Milestone.depositPaid); // 입금 완료 후 예매 취소 대기: 아직 결과 없음
        stats.milestone(9,RunStats.Milestone.depositPaid);stats.finish(9,RunStats.Outcome.confirmed); // 입금 예매: confirmed + depositPaid
        stats.finish(10,RunStats.Outcome.gaveUp);
        stats.finish(11,RunStats.Outcome.incomplete);
        var groups=stats.sampleLive(true).userGroups();
        assertThat(sum(groups)).isEqualTo(config.users());
        assertThat(groups).containsExactlyInAnyOrderEntriesOf(Map.of(
                "arriving",1L,"waiting",1L,"inside",5L,"revisitWait",1L,"left",1L,"bought",2L,"stopped",1L));
    }
    @Test void finishingEveryoneKeepsTheSum() {
        var config=new JsonCodec().config("{\"users\":5}");
        var stats=new RunStats(config,RunTime.system(),Collections.nCopies(5,Persona.FAST));
        stats.state(0,departed);stats.state(1,waiting);stats.state(2,holding);
        stats.finishPending(RunStats.Outcome.incomplete); // 실행 중지: 남은 사용자는 incomplete
        var groups=stats.sampleLive(false).userGroups();
        assertThat(sum(groups)).isEqualTo(5);
        assertThat(groups).containsEntry("stopped",5L);
        for(var key:List.of("arriving","waiting","inside","revisitWait","left","bought")) assertThat(groups).containsEntry(key,0L);
    }
}
