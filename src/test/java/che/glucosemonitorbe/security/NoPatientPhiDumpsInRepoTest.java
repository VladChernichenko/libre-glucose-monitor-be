package che.glucosemonitorbe.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD for BE critical: patient CGM/notes CSV exports must never be tracked in git.
 * Local untracked copies for backtests remain allowed; CI fails if they are committed.
 */
class NoPatientPhiDumpsInRepoTest {

    private static final Pattern ROOT_PHI_CSV = Pattern.compile(
            "^(cgm_readings_.*\\.csv|notes_.*\\.csv)$");

    @Test
    @DisplayName(".gitignore must ignore root patient CGM/notes CSV dumps")
    void gitignore_blocksRootPatientPhiCsvs() throws Exception {
        Path gitignore = repoRoot().resolve(".gitignore");
        assertThat(gitignore).exists();
        String text = Files.readString(gitignore);

        assertThat(text)
                .as("ignore root CGM exports")
                .contains("/cgm_readings_*.csv");
        assertThat(text)
                .as("ignore root notes exports")
                .contains("/notes_*.csv");
    }

    @Test
    @DisplayName("git must not track root patient CGM/notes CSV dumps")
    void gitIndex_mustNotContainRootPatientPhiCsvs() throws Exception {
        List<String> tracked = gitLsFiles();
        List<String> offenders = tracked.stream()
                .filter(p -> !p.contains("/"))
                .filter(p -> ROOT_PHI_CSV.matcher(p).matches())
                .collect(Collectors.toList());

        assertThat(offenders)
                .as("tracked root PHI CSV dumps — git rm and keep only under a private harness path")
                .isEmpty();
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve(".gitignore"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve(".gitignore"))) {
            return parent;
        }
        return cwd;
    }

    private static List<String> gitLsFiles() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "ls-files", "-z");
        pb.directory(repoRoot().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = r.lines().collect(Collectors.joining("\n"));
        }
        int code = p.waitFor();
        assertThat(code).as("git ls-files exit").isZero();

        List<String> files = new ArrayList<>();
        for (String part : out.split("\0", -1)) {
            if (!part.isEmpty()) {
                files.add(part.trim());
            }
        }
        return files;
    }
}
