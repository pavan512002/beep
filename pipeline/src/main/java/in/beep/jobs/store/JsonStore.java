package in.beep.jobs.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.JobTags;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Reads and writes every file the pipeline owns. All paths are relative to beep.root (the repo root). */
@Component
public class JsonStore {
    private final Path root;
    private final ObjectMapper mapper;

    public JsonStore(BeepProperties props, ObjectMapper mapper) {
        this.root = Path.of(props.root()).toAbsolutePath().normalize();
        this.mapper = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Path jobsFile()  { return root.resolve("web/data/jobs.json"); }
    public Path statsFile() { return root.resolve("web/data/stats.json"); }
    public Path cacheFile() { return root.resolve("state/tag-cache.json"); }
    public Path csvFile()   { return root.resolve("export/jobs.csv"); }
    public Path root()      { return root; }

    public List<Job> readJobs() throws IOException {
        if (!Files.exists(jobsFile())) return new ArrayList<>();
        return mapper.readValue(jobsFile().toFile(), new TypeReference<>() {});
    }

    public Map<String, JobTags> readTagCache() throws IOException {
        if (!Files.exists(cacheFile())) return new HashMap<>();
        return mapper.readValue(cacheFile().toFile(), new TypeReference<>() {});
    }

    public Map<String, Object> readStats() throws IOException {
        if (!Files.exists(statsFile())) return new LinkedHashMap<>();
        return mapper.readValue(statsFile().toFile(), new TypeReference<>() {});
    }

    /** Compact JSON for the site (smaller download); pretty JSON for files humans read in diffs. */
    public void write(Path file, Object value, boolean pretty) throws IOException {
        Files.createDirectories(file.getParent());
        var writer = pretty ? mapper.writerWithDefaultPrettyPrinter() : mapper.writer();
        // Sorted keys keep git diffs of the cache small and stable.
        if (value instanceof Map<?, ?> m && !(value instanceof LinkedHashMap)) value = new TreeMap<>(m);
        writer.writeValue(file.toFile(), value);
    }

    public void writeCsv(List<Job> jobs) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("id,title,company,location,role,cities,job_type,work_mode,fresher_fit,exp_min,exp_max,")
         .append("stipend_min_inr,stipend_max_inr,skills,confidence,low_confidence,sources,posted_at,url\n");
        for (Job j : jobs) {
            JobTags t = j.tags;
            List<Object> row = List.of(j.id, j.title, j.company, j.location,
                    t == null ? "" : t.role(),
                    t == null ? "" : String.join("; ", t.cities()),
                    t == null ? "" : t.jobType(), t == null ? "" : t.workMode(), t == null ? "" : t.fresherFit(),
                    t == null || t.expMinYears() == null ? "" : t.expMinYears(),
                    t == null || t.expMaxYears() == null ? "" : t.expMaxYears(),
                    t == null || t.stipendMinInr() == null ? "" : t.stipendMinInr(),
                    t == null || t.stipendMaxInr() == null ? "" : t.stipendMaxInr(),
                    t == null ? "" : String.join("; ", t.skills()),
                    t == null ? "" : t.confidence(), j.lowConfidence,
                    String.join("; ", j.sources), j.postedAt == null ? "" : j.postedAt.toString(), j.url);
            StringJoiner line = new StringJoiner(",");
            for (Object v : row) line.add(csv(String.valueOf(v)));
            b.append(line).append('\n');
        }
        Files.createDirectories(csvFile().getParent());
        // BOM so Excel / Google Sheets read ₹ and Indian names correctly
        Files.writeString(csvFile(), "﻿" + b, StandardCharsets.UTF_8);
    }

    private static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
