package in.beep.jobs.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.JobTags;
import in.beep.jobs.tag.TagCache;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public Map<String, TagCache.Entry> readTagCache() throws IOException {
        if (!Files.exists(cacheFile())) return new HashMap<>();
        return mapper.readValue(cacheFile().toFile(), new TypeReference<>() {});
    }

    public Map<String, Object> readStats() throws IOException {
        if (!Files.exists(statsFile())) return new LinkedHashMap<>();
        return mapper.readValue(statsFile().toFile(), new TypeReference<>() {});
    }

    /** Compact JSON for the site (smaller download); pretty JSON for files humans read in diffs. */
    public void write(Path file, Object value, boolean pretty) throws IOException {
        var writer = pretty ? mapper.writerWithDefaultPrettyPrinter() : mapper.writer();
        // Sorted keys keep git diffs of the cache small and stable.
        if (value instanceof Map<?, ?> m && !(value instanceof LinkedHashMap)) value = new TreeMap<>(m);
        replace(file, writer.writeValueAsBytes(value));
    }

    /**
     * Writes next to the target, then renames over it: a crash mid-write leaves the old file
     * whole instead of half a JSON document (jobs.json is also the next run's starting point).
     */
    static void replace(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void writeCsv(List<Job> jobs) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("id,title,company,location,role,cities,job_type,work_mode,fresher_fit,exp_min,exp_max,")
         .append("stipend_min_inr,stipend_max_inr,skills,implied_skills,in_india,confidence,low_confidence,sources,posted_at,url\n");
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
                    t == null || t.impliedSkills() == null ? "" : String.join("; ", t.impliedSkills()),
                    t == null || t.inIndia() == null ? "" : t.inIndia(),
                    t == null ? "" : t.confidence(), j.lowConfidence,
                    String.join("; ", j.sources), j.postedAt == null ? "" : j.postedAt.toString(), j.url);
            StringJoiner line = new StringJoiner(",");
            for (Object v : row) line.add(csv(v instanceof String s ? neutralizeFormula(s) : String.valueOf(v)));
            b.append(line).append('\n');
        }
        // BOM so Excel / Google Sheets read ₹ and Indian names correctly
        replace(csvFile(), ("﻿" + b).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Job text comes from third parties. A cell starting with = + - @ (or a tab/CR) is run as a
     * formula when the CSV is opened in Excel or Sheets, so it gets a leading apostrophe, which
     * spreadsheets show as plain text. Only text cells; the JSON keeps the original.
     */
    static String neutralizeFormula(String s) {
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) return "'" + s;
        return s;
    }

    static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
