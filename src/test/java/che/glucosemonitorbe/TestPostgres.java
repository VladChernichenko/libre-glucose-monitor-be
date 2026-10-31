package che.glucosemonitorbe;

/** Single source of truth for the Postgres image used by Testcontainers (includes pgvector). */
public final class TestPostgres {

    public static final String IMAGE = "pgvector/pgvector:pg16";

    private TestPostgres() {
    }
}
