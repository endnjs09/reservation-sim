package dev.endnjs.reservation.admission;

import dev.endnjs.reservation.common.*;
import dev.endnjs.reservation.metrics.*;
import dev.endnjs.reservation.sale.SaleService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdmissionKeyTest {
    private final Instant now=Instant.parse("2026-10-04T00:00:00Z");
    private final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    private final SaleService sale=mock(SaleService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final KeyRegistry registry=new KeyRegistry();
    private final UUID kid=UUID.randomUUID();
    private AdmissionKeyChecker checker;
    private AdmissionKeyAuditor auditor;
    @BeforeEach void setup() {
        when(sale.runEpoch()).thenReturn("current");
        checker=new AdmissionKeyChecker("test-secret",true,sale,clock,registry,jdbc);
        auditor=new AdmissionKeyAuditor("test-secret",sale,clock,jdbc);
    }
    private String sign(Map<String,Object> changes,String secret) throws Exception {
        var body=new LinkedHashMap<String,Object>(Map.of("kid",kid.toString(),"uid","owner","iat",now.minusSeconds(1).toEpochMilli(),"exp",now.plusSeconds(1).toEpochMilli(),"run","current"));
        body.putAll(changes);
        String message="v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(JsonMapper.builder().build().writeValueAsBytes(body));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return message+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
    private String key(Map<String,Object> changes) throws Exception { return sign(changes,"test-secret"); }
    private void denied(String key,ProtectedRequest target,ErrorCode code) {
        assertThatThrownBy(()->checker.check(key,target)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    @Test void validSignedKeyNeedsNoDatabaseOrQueueCall() throws Exception {
        String key=key(Map.of());var target=new ProtectedRequest("owner",null);
        assertThat(checker.check(key,target).kid()).isEqualTo(kid);assertThat(auditor.valid(key,target)).isTrue();
        verifyNoInteractions(jdbc);
    }
    @ParameterizedTest @ValueSource(strings={"","token","v2.e30.e30","v1.e30.e30","v1.e30=.e30","v1.a.b.c"})
    void malformedKeyFailsBothImplementations(String key) {
        denied(key,new ProtectedRequest(null,null),ErrorCode.KEY_INVALID);assertThat(auditor.valid(key,new ProtectedRequest(null,null))).isFalse();
    }
    @Test void missingKeyFails403() { denied(null,new ProtectedRequest(null,null),ErrorCode.KEY_INVALID); }
    @Test void signatureRunAndUserEachRejectBeforeExpiry() throws Exception {
        denied(sign(Map.of(),"wrong"),new ProtectedRequest("owner",null),ErrorCode.KEY_INVALID);
        denied(key(Map.of("run","previous")),new ProtectedRequest("owner",null),ErrorCode.KEY_INVALID);
        denied(key(Map.of()),new ProtectedRequest("other",null),ErrorCode.KEY_INVALID);
        denied(key(Map.of("exp","not-a-number")),new ProtectedRequest("owner",null),ErrorCode.KEY_INVALID);
        assertThat(auditor.valid(key(Map.of("run","previous")),new ProtectedRequest("owner",null))).isFalse();
        assertThat(auditor.valid(key(Map.of()),new ProtectedRequest("other",null))).isFalse();
    }
    @Test void revocationPrecedesExpiryAndResetClearsIt() throws Exception {
        String key=key(Map.of());registry.revoke(kid);denied(key,new ProtectedRequest("owner",null),ErrorCode.KEY_REVOKED);
        registry.reset();assertThat(checker.check(key,new ProtectedRequest("owner",null)).kid()).isEqualTo(kid);
    }
    @Test void expiryAtExactBoundaryNeverPermitsSeatsOrNewHolds() throws Exception {
        String key=key(Map.of("exp",now.toEpochMilli()));var target=new ProtectedRequest("owner",null);
        denied(key,target,ErrorCode.KEY_EXPIRED);assertThat(auditor.valid(key,target)).isFalse();verifyNoInteractions(jdbc);
    }
    @Test void expiredKeyPermitsOnlyItsOwnOngoingReservation() throws Exception {
        String key=key(Map.of("exp",now.toEpochMilli()));var target=new ProtectedRequest("owner",7L);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(7L),eq("owner"))).thenReturn(true);
        assertThat(checker.check(key,target).kid()).isEqualTo(kid);assertThat(auditor.valid(key,target)).isTrue();
        denied(key,new ProtectedRequest("owner",8L),ErrorCode.KEY_EXPIRED);
        denied(key,new ProtectedRequest("other",7L),ErrorCode.KEY_INVALID);
    }
    @Test void independentAuditorCountsBypassedMainValidationOnSuccessfulRequest() throws Exception {
        var metrics=new MetricsCollector(clock);
        var filter=new RequestMetricsFilter(metrics,checker,auditor,jdbc);
        var request=new MockHttpServletRequest("POST","/holds");request.setContentType("application/json");
        request.setContent("{\"userId\":\"owner\",\"seatIds\":[1]}".getBytes(StandardCharsets.UTF_8));request.addHeader("X-Admission-Key",key(Map.of("run","old")));
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(201));
        assertThat(metrics.counters()).containsEntry("acceptedInvalidKeys",1L);
        assertThat(response.getStatus()).isEqualTo(201);
    }
    @Test void rejectedRequestsAndAdmissionDisabledDoNotCountAuditViolation() throws Exception {
        var metrics=new MetricsCollector(clock);var filter=new RequestMetricsFilter(metrics,checker,auditor,jdbc);
        var request=new MockHttpServletRequest("GET","/seats");
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(403));
        assertThat(metrics.counters()).containsEntry("acceptedInvalidKeys",0L);
        var disabled=new AdmissionKeyChecker("test-secret",false,sale,clock,registry,jdbc);
        new RequestMetricsFilter(metrics,disabled,auditor,jdbc).doFilter(new MockHttpServletRequest("GET","/seats"),new MockHttpServletResponse(),(req,res)->{});
        assertThat(metrics.counters()).containsEntry("acceptedInvalidKeys",0L);
    }
    @Test void auditorCapturesExpiryExceptionBeforeSuccessfulMutationRemovesHold() throws Exception {
        String key=key(Map.of("exp",now.toEpochMilli()));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(7L),eq("owner"))).thenReturn(true);
        var metrics=new MetricsCollector(clock);var request=new MockHttpServletRequest("POST","/holds/7/deposit");
        request.setContent("{\"userId\":\"owner\"}".getBytes(StandardCharsets.UTF_8));request.addHeader("X-Admission-Key",key);
        new RequestMetricsFilter(metrics,checker,auditor,jdbc).doFilter(request,new MockHttpServletResponse(),(req,res)-> {
            when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(7L),eq("owner"))).thenReturn(false);
            ((jakarta.servlet.http.HttpServletResponse)res).setStatus(200);
        });
        assertThat(metrics.counters()).containsEntry("acceptedInvalidKeys",0L);
    }
    @Test void oldResponseAfterResetCannotIncrementTheNewRunAuditCounter() throws Exception {
        var metrics=new MetricsCollector(clock);var request=new MockHttpServletRequest("GET","/seats");
        new RequestMetricsFilter(metrics,checker,auditor,jdbc).doFilter(request,new MockHttpServletResponse(),(req,res)-> {
            metrics.reset();((jakarta.servlet.http.HttpServletResponse)res).setStatus(200);
        });
        assertThat(metrics.counters()).containsEntry("acceptedInvalidKeys",0L);
    }
}
