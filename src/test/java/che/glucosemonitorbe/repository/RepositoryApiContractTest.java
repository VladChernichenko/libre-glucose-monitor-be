package che.glucosemonitorbe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural contract tests for Spring Data repository interfaces.
 * All tests use reflection so no DB is needed - they verify method signatures only.
 * Tests FAIL against current buggy code; PASS once fixed.
 */
class RepositoryApiContractTest {

    // -- D4: NoteRepository.findByIdAndUserId should return Optional<Note> ----

    /**
     * // BUG: D4 - NoteRepository.findByIdAndUserId returns bare Note (null when not found)
     * instead of Optional<Note>, forcing callers to do null-checks and causing NPEs.
     */
    @Test
    void d4_noteRepository_findByIdAndUserId_mustReturnOptional() throws Exception {
        Method method = NoteRepository.class.getMethod("findByIdAndUserId", UUID.class, UUID.class);

        // BUG: currently returns Note.class - this assertion FAILS
        assertThat(method.getReturnType())
                .as("NoteRepository.findByIdAndUserId must return Optional<Note>, not bare Note (BUG: D4)")
                .isEqualTo(Optional.class);
    }

    // -- P2: NoteRepository.findByUserIdOrderByTimestampDesc lacks Pageable ---

    /**
     * // BUG: P2 - NoteRepository.findByUserIdOrderByTimestampDesc has no Pageable param,
     * so it loads ALL notes for a user into memory - unbounded result set.
     */
    @Test
    void p2_noteRepository_findAllByUserId_mustAcceptPageable() {
        boolean hasPageableVariant = false;
        for (Method m : NoteRepository.class.getMethods()) {
            if (m.getName().equals("findByUserIdOrderByTimestampDesc")) {
                for (Class<?> param : m.getParameterTypes()) {
                    if (Pageable.class.isAssignableFrom(param)) {
                        hasPageableVariant = true;
                        break;
                    }
                }
            }
        }
        // BUG: no Pageable overload exists - this FAILS
        assertThat(hasPageableVariant)
                .as("NoteRepository must have a findByUserIdOrderByTimestampDesc(UUID, Pageable) "
                        + "variant to avoid loading all rows (BUG: P2)")
                .isTrue();
    }

    // -- P3: CgmReadingRepository.findByUserId lacks limit/Pageable -

    /**
     * // BUG: P3 - CgmReadingRepository.findByUserIdOrderByDateTimestampAsc returns
     * all CGM entries for a user without any limit, causing OOM on large data sets.
     */
    @Test
    void p3_cgmReadingRepository_findAll_mustAcceptPageable() {
        boolean hasPageableVariant = false;
        for (Method m : CgmReadingRepository.class.getMethods()) {
            if (m.getName().equals("findByUserIdOrderByDateTimestampAsc")) {
                for (Class<?> param : m.getParameterTypes()) {
                    if (Pageable.class.isAssignableFrom(param)) {
                        hasPageableVariant = true;
                        break;
                    }
                }
            }
        }
        // BUG: no Pageable overload exists - this FAILS
        assertThat(hasPageableVariant)
                .as("CgmReadingRepository must have a "
                        + "findByUserIdOrderByDateTimestampAsc(UUID, Pageable) variant (BUG: P3)")
                .isTrue();
    }
}
