package com.gek.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads and applies synonym mappings from resources/data/synonyms/&lt;lang&gt;.json (Java port of Python logic). */
public class SynonymReplacer {
    private static final Logger log = LoggerFactory.getLogger(SynonymReplacer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Map<String, String>> byLang = new ConcurrentHashMap<>();

    public static SynonymReplacer load(Path synonymsDir) {
        SynonymReplacer r = new SynonymReplacer();
        if (synonymsDir == null || !Files.isDirectory(synonymsDir)) return r;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(synonymsDir, "*.json")) {
            for (Path p : ds) {
                String lang = stripExt(p.getFileName().toString());
                try {
                    JsonNode root = MAPPER.readTree(p.toFile());
                    Map<String, String> map = new LinkedHashMap<>();
                    JsonNode syn = root.path("synonyms");
                    if (syn.isObject()) {
                        syn.fields().forEachRemaining(e -> {
                            if (e.getValue().isTextual()) map.put(e.getKey(), e.getValue().asText());
                        });
                    } else if (root.isObject() && !root.has("locale") && !root.has("language")) {
                        // tolerate flat map files
                        root.fields().forEachRemaining(e -> { if (e.getValue().isTextual()) map.put(e.getKey(), e.getValue().asText()); });
                    }
                    if (!map.isEmpty()) r.byLang.put(lang, Collections.unmodifiableMap(map));
                } catch (Exception e) {
                    log.debug("synonym load failed for {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("synonym dir scan failed: {}", e.getMessage());
        }
        return r;
    }

    public String apply(String text, String lang) {
        if (text == null || text.isEmpty() || lang == null) return text;
        Map<String, String> map = byLang.get(lang);
        if (map == null || map.isEmpty()) return text;
        String out = text;
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) continue;
            try {
                Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\b");
                Matcher m = p.matcher(out);
                out = m.replaceAll(Matcher.quoteReplacement(e.getValue() == null ? "" : e.getValue()));
            } catch (Exception ex) {
                // skip bad entries
            }
        }
        return out;
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> c = new LinkedHashMap<>();
        byLang.forEach((k, v) -> c.put(k, v.size()));
        return c;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
