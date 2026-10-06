package dev.endnjs.reservation.metrics;

import dev.endnjs.reservation.admission.*;
import dev.endnjs.reservation.sale.SaleService;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 8.2 예약 서버 키는 depositPay·cancel 하나씩 (예전에는 deposit.pay·reservation.cancel과 같은 값이 두 이름으로 나갔음). */
class EndpointNamesTest {
    @Test void depositPayAndCancelAreRecordedUnderOneNameEach() throws Exception {
        var clock=Clock.systemUTC();var metrics=new MetricsCollector(clock);
        var sale=mock(SaleService.class);var jdbc=mock(JdbcTemplate.class);
        var checker=new AdmissionKeyChecker("secret",false,sale,clock,new KeyRegistry(),jdbc);
        var filter=new RequestMetricsFilter(metrics,checker,new AdmissionKeyAuditor("secret",sale,clock,jdbc),jdbc);
        filter.doFilter(new MockHttpServletRequest("POST","/deposits/3/pay"),new MockHttpServletResponse(),(req,res) -> {});
        filter.doFilter(new MockHttpServletRequest("POST","/reservations/3/cancel"),new MockHttpServletResponse(),(req,res) -> {});
        filter.doFilter(new MockHttpServletRequest("POST","/reservations/3/cancel"),new MockHttpServletResponse(),(req,res) -> {});
        var sample=metrics.sample(Instant.now());
        assertThat(sample.endpoints()).containsKeys("depositPay","cancel").doesNotContainKeys("deposit.pay","reservation.cancel");
        assertThat(sample.cumulative()).containsKeys("depositPay","cancel").doesNotContainKeys("deposit.pay","reservation.cancel");
        assertThat(((Number)((java.util.Map<?,?>)sample.cumulative().get("depositPay")).get("count")).longValue()).isEqualTo(1);
        assertThat(((Number)((java.util.Map<?,?>)sample.cumulative().get("cancel")).get("count")).longValue()).isEqualTo(2);
    }
}
