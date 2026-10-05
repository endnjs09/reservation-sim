package dev.endnjs.simulator.service;

import dev.endnjs.simulator.engine.RunConfig;
import dev.endnjs.simulator.http.JsonCodec;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PresetStore {
    private final Path directory;
    private final JsonCodec json = new JsonCodec();
    public PresetStore(@Value("${simulator.presets-dir:./presets}") String directory) throws IOException {
        this.directory = Path.of(directory).toAbsolutePath();
        Files.createDirectories(this.directory);
        if (!Files.exists(this.directory.resolve("default.json"), LinkOption.NOFOLLOW_LINKS)) save("default", RunConfig.defaults());
    }
    private Path path(String name) {
        if (!name.matches("[\\p{L}\\p{N}_-]{1,64}")) throw new ApiFailure(400, "INVALID_PRESET_NAME", "이름은 문자·숫자·밑줄·하이픈 1~64자여야 합니다.");
        Path path = directory.resolve(name + ".json");
        if (Files.isSymbolicLink(path)) throw new ApiFailure(400, "INVALID_PRESET_NAME", "심볼릭 링크는 사용할 수 없습니다.");
        return path;
    }
    public List<String> list() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString()).filter(name -> name.matches("[\\p{L}\\p{N}_-]{1,64}\\.json"))
                    .map(name -> name.substring(0, name.length() - 5)).sorted().toList();
        }
    }
    public RunConfig get(String name) throws IOException {
        Path file = path(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new ApiFailure(404, "PRESET_NOT_FOUND", "프리셋이 없습니다.");
        return json.readConfig(file);
    }
    public synchronized RunConfig save(String name, RunConfig config) throws IOException {
        JsonFiles.write(path(name), json.encode(config));
        return config;
    }
}
