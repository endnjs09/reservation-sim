package dev.endnjs.simulator.service;

import dev.endnjs.simulator.http.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static dev.endnjs.simulator.service.RunStore.object;
import static dev.endnjs.simulator.service.RunMeasurements.number;

public final class RunComparison {
    private static final Set<String> VARIABLES=Set.of("strategy","dbBackstop","poolMax","threadsMax","virtualThreads","queueMode");
    private static final Set<String> EXCLUDED=Set.of("label","notes","thresholds","targets","requestTimeoutMs","busyMaxExtraSec","earlyQuitRate");
    private record Metric(String key,String label,String unit,String better) {}
    private static final List<Metric> METRICS=List.of(
        new Metric("p95Max","최대 p95","ms","low"),new Metric("p99Max","최대 p99","ms","low"),new Metric("p95Rush","폭주 구간 평균 p95","ms","low"),
        new Metric("sloBreachSec","SLO 초과 시간","s","low"),new Metric("errPctRush","폭주 구간 에러율","%","low"),new Metric("failPct","실패율","%","low"),
        new Metric("conflict","409 충돌 누적","건","low"),new Metric("poolPctMax","최대 풀 사용률","%","low"),new Metric("poolSatSec","풀 포화 시간","s","low"),
        new Metric("lockWaitsMax","최대 락 대기","개","low"),new Metric("rpsMax","최대 처리량","rps",null),new Metric("soldOutAtSec","매진 시각","s",null),
        new Metric("seatsSold","판매","석",null),new Metric("invariants","불변식 통과 수","개","high"));
    public static String fingerprint(Map<String,Object> config) {
        var scenario=new TreeMap<>(config);VARIABLES.forEach(scenario::remove);EXCLUDED.forEach(scenario::remove);
        String canonical=new JsonCodec().encode(sorted(scenario));
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Object sorted(Object value) {
        if(value instanceof Map<?,?> map) { var result=new TreeMap<String,Object>();map.forEach((k,v) -> result.put(k.toString(),sorted(v)));return result; }
        if(value instanceof List<?> list) return list.stream().map(RunComparison::sorted).toList();return value;
    }
    public static Map<String,Object> compare(Map<String,Object> a,Map<String,Object> b,Long invariantsA,Long invariantsB) { return compare(a,b,invariantsA,invariantsB,false,false); }
    /** swapA·swapB: 그 실행의 events에 SWAP_USED가 있는지 (10.4, ENV_DEGRADED). */
    public static Map<String,Object> compare(Map<String,Object> a,Map<String,Object> b,Long invariantsA,Long invariantsB,boolean swapA,boolean swapB) {
        var warnings=new ArrayList<String>();var aConfig=flatten(object(a.get("config")));var bConfig=flatten(object(b.get("config")));
        for(String key:EXCLUDED) { aConfig.keySet().removeIf(k -> k.equals(key)||k.startsWith(key+"."));bConfig.keySet().removeIf(k -> k.equals(key)||k.startsWith(key+".")); }
        for(String key:VARIABLES) {
            if(!key.equals("queueMode")) {
                aConfig.remove(key);bConfig.remove(key);aConfig.put("server."+key,object(a.get("server")).get(key));bConfig.put("server."+key,object(b.get("server")).get(key));
            } else { aConfig.put(key,a.get(key));bConfig.put(key,b.get(key)); }
        }
        var keys=new TreeSet<String>();keys.addAll(aConfig.keySet());keys.addAll(bConfig.keySet());var differences=new ArrayList<Map<String,Object>>();int variables=0;
        for(String key:keys) if(!Objects.equals(sorted(aConfig.get(key)),sorted(bConfig.get(key)))) {
            var diff=new LinkedHashMap<String,Object>();diff.put("key",key);diff.put("a",aConfig.get(key));diff.put("b",bConfig.get(key));differences.add(diff);
            if(VARIABLES.contains(key.replaceFirst("^server\\.",""))) variables++;
        }
        boolean same=a.get("fingerprint")!=null && (Objects.equals(a.get("fingerprint"),b.get("fingerprint")) || legacyFingerprintMatches(a,b));
        if(!same) warnings.add("SCENARIO_DIFFERS");if(variables>=2) warnings.add("MULTIPLE_VARIABLES");
        if(number(a.get("timeScale"))!=1 || number(b.get("timeScale"))!=1) warnings.add("TIME_SCALED");
        if(!"COMPLETED".equals(a.get("status")) || !"COMPLETED".equals(b.get("status"))) warnings.add("NOT_COMPLETED");
        if(number(a.get("schemaVersion"))<5 || number(b.get("schemaVersion"))<5) warnings.add("OLD_SCHEMA");
        var envA=object(a.get("environment"));var envB=object(b.get("environment"));
        if(!Objects.equals(envA.get("cpuCores"),envB.get("cpuCores")) || !Objects.equals(envA.get("os"),envB.get("os")) || !Objects.equals(bench(envA),bench(envB))) warnings.add("ENV_DIFFERS");
        if((a.get("clockOffsetsMs")!=null)!=(b.get("clockOffsetsMs")!=null)) warnings.add("CLOCK_MODEL_DIFFERS");
        if(swapA || swapB) warnings.add("ENV_DEGRADED");
        var metrics=new ArrayList<Map<String,Object>>();
        for(var metric:METRICS) {
            Object av=value(a,metric.key(),invariantsA),bv=value(b,metric.key(),invariantsB);String winner=null;
            if(av instanceof Number && bv instanceof Number && metric.better()!=null && number(av)!=number(bv)) winner=(number(av)<number(bv))==metric.better().equals("low") ? "a" : "b";
            var row=new LinkedHashMap<String,Object>();row.put("key",metric.key());row.put("label",metric.label());row.put("unit",metric.unit());row.put("a",av);row.put("b",bv);row.put("better",metric.better());row.put("winner",winner);
            row.put("diffPct",av instanceof Number && bv instanceof Number && number(av)!=0 ? Math.round((number(bv)-number(av))/number(av)*1000)/10.0 : null);metrics.add(row);
        }
        var result=new LinkedHashMap<String,Object>();result.put("a",a.get("runId"));result.put("b",b.get("runId"));result.put("comparable",!warnings.contains("OLD_SCHEMA"));result.put("sameScenario",same);result.put("configDiff",differences);result.put("warnings",warnings);result.put("warningNotes",notes(warnings));result.put("metrics",metrics);result.put("verdict",verdict(metrics,warnings));return result;
    }
    /** 10.4: 측정 환경 비교는 설정값(포트·힙·CPU·DB·연결 대기 줄 acceptCount)만. verified·mismatch는 관측값이라 제외한다. */
    private static Map<String,Object> bench(Map<String,Object> environment) {
        if(!(environment.get("bench") instanceof Map<?,?> bench)) return null;
        var result=new TreeMap<String,Object>();for(String key:List.of("ports","heap","cpus","db","acceptCount")) result.put(key,sorted(bench.get(key)));return result;
    }
    /** 9.2 경고 중 정해진 문구가 있는 것. */
    private static Map<String,String> notes(List<String> warnings) {
        var result=new LinkedHashMap<String,String>();
        if(warnings.contains("CLOCK_MODEL_DIFFERS")) result.put("CLOCK_MODEL_DIFFERS","누적 지표는 판매 시간이 달라 참고만, 폭주 구간 지표(p95Rush, errPctRush, poolSatSec)로 비교하세요");
        return result;
    }
    /** 9.1: records saved before earlyQuitRate was excluded hashed it in; recompute only for them so a seed-identical scenario still matches. */
    private static boolean legacyFingerprintMatches(Map<String,Object> a,Map<String,Object> b) {
        if(!(a.get("config") instanceof Map<?,?> ca) || !(b.get("config") instanceof Map<?,?> cb) || b.get("fingerprint")==null) return false;
        if(!ca.containsKey("earlyQuitRate") && !cb.containsKey("earlyQuitRate")) return false;
        return fingerprint(object(ca)).equals(fingerprint(object(cb)));
    }
    private static Object value(Map<String,Object> run,String key,Long invariants) {
        if(key.equals("invariants")) return invariants;if(key.equals("seatsSold")) return run.get(key);
        if(key.equals("conflict")) return object(run.get("errorClasses")).get(key);return object(run.get("signals")).get(key);
    }
    private static String verdict(List<Map<String,Object>> metrics,List<String> warnings) {
        String winner=(String)metrics.getFirst().get("winner");String text;
        if(winner==null) text=metrics.getFirst().get("a")==null || metrics.getFirst().get("b")==null ? "응답 시간 비교 데이터가 없습니다." : "최대 p95 응답 시간은 같습니다.";
        else {
            text=winner.toUpperCase(Locale.ROOT)+"의 최대 p95 응답 시간이 더 낮습니다.";
            Map<String,Object> reason=null;double gap=-1;
            for(var row:metrics.subList(1,10)) {
                if(winner.equals(row.get("winner"))) {
                    double difference=row.get("diffPct") instanceof Number ? Math.abs(number(row.get("diffPct"))) : Math.abs(number(row.get("b"))-number(row.get("a")));
                    if(difference>gap) { gap=difference;reason=row; }
                }
            }
            if(reason!=null) text+=" "+(winner.equals("a") ? "B" : "A")+"의 "+reason.get("label")+"도 더 높았습니다.";
        }
        if(!warnings.isEmpty()) text+=" (주의: "+String.join(" · ",warnings)+")";return text;
    }
    private static Map<String,Object> flatten(Map<String,Object> config) { var result=new TreeMap<String,Object>();flatten("",config,result);return result; }
    private static void flatten(String prefix,Map<String,Object> config,Map<String,Object> output) {
        config.forEach((key,value) -> { String path=prefix.isEmpty() ? key : prefix+"."+key;if(value instanceof Map) flatten(path,object(value),output);else output.put(path,sorted(value)); });
    }
}
