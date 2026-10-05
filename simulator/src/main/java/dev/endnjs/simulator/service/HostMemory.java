package dev.endnjs.simulator.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** /proc/meminfo 읽기 (8.5 memAvailableMbAtStart, 10.4 스왑 감시). 못 읽는 환경이면 값이 null이다. */
record HostMemory(Long availableMb, Long swapUsedMb) {
    static final Path MEMINFO=Path.of("/proc/meminfo");

    static HostMemory read() { return read(MEMINFO); }

    static HostMemory read(Path file) {
        try { return parse(Files.readString(file)); }
        catch(IOException|RuntimeException unreadable) { return new HostMemory(null,null); }
    }

    static HostMemory parse(String text) {
        var kb=new HashMap<String,Long>();
        for(String line:text.split("\n")) {
            String[] parts=line.trim().split("\\s+");
            if(parts.length>=2 && parts[0].endsWith(":")) {
                try { kb.put(parts[0].substring(0,parts[0].length()-1),Long.parseLong(parts[1])); } catch(NumberFormatException ignored) {}
            }
        }
        Long available=mb(kb,"MemAvailable");
        Long swap=kb.containsKey("SwapTotal") && kb.containsKey("SwapFree") ? (kb.get("SwapTotal")-kb.get("SwapFree"))/1024 : null;
        return new HostMemory(available,swap);
    }

    private static Long mb(Map<String,Long> kb,String key) { return kb.containsKey(key) ? kb.get(key)/1024 : null; }
}
