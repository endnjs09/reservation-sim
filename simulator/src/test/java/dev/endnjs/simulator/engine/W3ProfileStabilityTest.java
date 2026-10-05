package dev.endnjs.simulator.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 10.3: earlyQuitRate 제거 후에도 같은 seed의 사용자 행동이 예전과 같아야 한다 (난수 1회는 그대로 뽑아 버림). */
class W3ProfileStabilityTest {
    // earlyQuitRate가 있던 코드(기본값 0.1)에서 뽑은 기본 설정 사용자 2000명 프로필의 SHA-256.
    private static final String PROFILES_BEFORE_REMOVAL="019eb7e2b585e14536a7f5ac57ee3614d0a0d4c4ac34081ac2f242ccf9f27980";

    static String profileDigest(RunConfig config) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        for(int i=0;i<config.users();i++) {
            var p=VirtualUser.profile(config,i);
            String row=p.persona()+"|"+p.arrivalMillis()+"|"+p.churn()+"|"+p.ticketCount()+"|"+p.adjacentRequired()+"|"+p.deposit()
                    +"|"+p.payDeposit()+"|"+p.abandon()+"|"+p.cancelPurchase()+"|"+p.depositFraction()+"|"+p.cancelFraction()+"|"+p.random().nextLong()+"\n";
            digest.update(row.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    @Test void sameSeedGivesTheSameUsersAsBeforeEarlyQuitRateWasRemoved() throws Exception {
        assertThat(profileDigest(new JsonCodec().config("{}"))).isEqualTo(PROFILES_BEFORE_REMOVAL);
    }
}
