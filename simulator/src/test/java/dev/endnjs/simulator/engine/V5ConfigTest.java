package dev.endnjs.simulator.engine;

import java.util.Map;
import java.util.List;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class V5ConfigTest {
    private final JsonCodec json=new JsonCodec();
    @Test void defaultsExposeEveryV04SettingAndEnoughTimeToObserveSaleEnd() {
        var config=json.config("{}");assertThat(config.maxSeatsPerUser()).isEqualTo(4);
        assertThat(config.ticketCountMix()).containsExactlyInAnyOrderEntriesOf(Map.of("1",30,"2",55,"3",10,"4",5));
        assertThat(config.paymentMix()).containsExactlyInAnyOrderEntriesOf(Map.of("card",85,"deposit",15));
        assertThat(config.depositDeadlineSec()).isEqualTo(60);assertThat(config.depositNoPayRate()).isEqualTo(.4);
        assertThat(config.returnDelaySec()).isEqualTo(30);assertThat(config.reopenWindowSec()).isEqualTo(50);
        assertThat(config.adjacentRequiredRate()).isEqualTo(.9);assertThat(config.cancelAfterPurchaseRate()).isEqualTo(.02);
        assertThat(config.priceStepSec()).containsKeys("fast","normal","slow");
        assertThat(config.timeLimitSec()).isEqualTo(1800);assertThat(config.realDuration(config.timeLimitSec()).toSeconds()).isEqualTo(450);
        assertThat(config.timeLimitSec()).isGreaterThan(config.saleDurationSec()+config.holdTtlSec());
    }
    @ParameterizedTest @ValueSource(strings={
            "{\"ticketCountMix\":{\"1\":0,\"2\":0,\"3\":0,\"4\":0}}",
            "{\"ticketCountMix\":{\"1\":-1}}","{\"ticketCountMix\":{\"5\":1}}","{\"maxSeatsPerUser\":1}",
            "{\"paymentMix\":{\"card\":0,\"deposit\":0}}","{\"paymentMix\":{\"cash\":100}}",
            "{\"adjacentRequiredRate\":1.1}","{\"depositNoPayRate\":-0.1}","{\"cancelAfterPurchaseRate\":2}",
            "{\"depositDeadlineSec\":0}","{\"returnDelaySec\":0}","{\"reopenWindowSec\":0}",
            "{\"priceStepSec\":{\"fast\":{\"min\":5,\"max\":1}}}"
    }) void invalidDistributionsAndTimingsAreRejected(String input) { assertThatThrownBy(() -> json.config(input)).isInstanceOf(RuntimeException.class); }
    @Test void userSeedProducesExpectedIndependentAttributeDistributions() {
        var config=RunConfig.defaults();int samples=100000;int[] tickets=new int[4];int deposits=0,noPay=0,adjacent=0,cancel=0,abandoned=0;
        int[][] mix=new int[3][3];
        for(int i=0;i<samples;i++) {
            var profile=VirtualUser.profile(config,i);tickets[profile.ticketCount()-1]++;
            if(profile.deposit()) { deposits++;if(!profile.payDeposit()) noPay++; }
            if(profile.adjacentRequired()) adjacent++;if(profile.cancelPurchase()) cancel++;if(profile.abandon()) abandoned++;
            mix[profile.persona().ordinal()][profile.churn().ordinal()]++;
            assertThat(profile.depositFraction()).isBetween(.1,.9);assertThat(profile.cancelFraction()).isBetween(0.,.8);
        }
        double[] expected={.3,.55,.1,.05};for(int i=0;i<4;i++) assertThat(tickets[i]/(double)samples).isCloseTo(expected[i],within(.009));
        assertThat(deposits/(double)samples).isCloseTo(.15,within(.006));assertThat(noPay/(double)deposits).isCloseTo(.4,within(.02));
        assertThat(adjacent/(double)samples).isCloseTo(.9,within(.006));assertThat(cancel/(double)samples).isCloseTo(.02,within(.004));
        assertThat(abandoned/(double)samples).isCloseTo(.04,within(.005));
        double[] churn={.7,.2,.1};for(var row:mix) for(int i=0;i<3;i++) assertThat(row[i]/(double)java.util.Arrays.stream(row).sum()).isCloseTo(churn[i],within(.02));
        for(int i=0;i<100;i++) {
            var a=VirtualUser.profile(config,i);var b=VirtualUser.profile(config,i);
            assertThat(a.ticketCount()).isEqualTo(b.ticketCount());assertThat(a.deposit()).isEqualTo(b.deposit());
            assertThat(a.abandon()).isEqualTo(b.abandon());assertThat(a.cancelPurchase()).isEqualTo(b.cancelPurchase());
            assertThat(a.depositFraction()).isEqualTo(b.depositFraction());assertThat(a.random().nextLong()).isEqualTo(b.random().nextLong());
        }
    }
    @ParameterizedTest @ValueSource(ints={1,2,4})
    void executionDeadlineScalesButNetworkTimeoutRemainsRealTime(int scale) throws Exception {
        var time=new V5EngineTest.Time();var control=new RunControl(time);control.start(120,scale);
        assertThat(control.requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(10));
        time.sleep(120000/scale-1);control.check();time.sleep(1);
        assertThatThrownBy(control::check).isInstanceOf(InterruptedException.class);
    }
}
