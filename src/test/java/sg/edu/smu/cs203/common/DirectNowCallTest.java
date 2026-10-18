package sg.edu.smu.cs203.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DirectNowCallTest {

    // Matches now() with EMPTY parentheses. now(clock) is allowed.
    private static final Pattern BANNED = Pattern.compile(
            "(LocalDateTime|LocalDate|LocalTime|Instant|ZonedDateTime|OffsetDateTime)\\s*\\.\\s*now\\s*\\(\\s*\\)"
            + "|System\\s*\\.\\s*currentTimeMillis\\s*\\("
            + "|new\\s+Date\\s*\\(\\s*\\)");

    // Files allowed to break the rule. DemoClock legitimately falls back to real time.
    private static final List<String> EXEMPT = List.of("DemoClock.java", "TimeConfig.java");

    @Test
    void noBusinessCodeCallsNowWithoutTheClock() throws IOException {
        Path sourceRoot = Paths.get("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String name = file.getFileName().toString();
                if (EXEMPT.contains(name)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().startsWith("//")) {
                        continue; // ignore commented-out lines
                    }
                    if (BANNED.matcher(line).find()) {
                        violations.add(file + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Use the shared Clock instead of now(). Violations:\n" + String.join("\n", violations));
    }
}
