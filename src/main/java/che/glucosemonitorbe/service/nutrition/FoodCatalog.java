package che.glucosemonitorbe.service.nutrition;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Read-only SQLite catalog of per-100 g macros and glycemic index. The file is created from the
 * classpath seed the first time it is missing, then opened so later requests cannot write it.
 */
@Component
public class FoodCatalog {

    private final Connection connection;

    @Autowired
    public FoodCatalog(@Value("${app.food-catalog.path:data/food-composition.sqlite}") String path) {
        this(Path.of(path), read("food/schema.sql"), read("food/seed.sql"));
    }

    FoodCatalog(Path db, String schemaSql, String seedSql) {
        try {
            Files.createDirectories(db.toAbsolutePath().getParent() == null
                    ? Path.of(".") : db.toAbsolutePath().getParent());
            if (!Files.exists(db) || Files.size(db) == 0) {
                try (Connection init = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
                    exec(init, schemaSql);
                    exec(init, seedSql);
                }
            }
            connection = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro");
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not open the food catalog at " + db, e);
        }
    }

    /** Per-100 g row for an exact alias, or the label's last word. Null when nothing matches. */
    public synchronized Match find(String label) {
        if (label == null || label.isBlank()) return null;
        String key = label.trim().toLowerCase();
        Match exact = lookup(key);
        if (exact != null) return exact;
        int space = key.lastIndexOf(' ');
        if (space < 0 || space == key.length() - 1) return null;
        return lookup(key.substring(space + 1));
    }

    private Match lookup(String alias) {
        String sql = """
                SELECT n.carbs_100g, n.fiber_100g, n.protein_100g, n.fat_100g, n.kcal_100g,
                       g.gi, a.typical_serving_g
                FROM food_alias a
                JOIN food_nutrient n ON n.id = a.nutrient_id
                LEFT JOIN gi_reference g ON g.id = a.gi_id
                WHERE a.alias = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, alias);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                double gi = rs.getDouble("gi");
                Double giValue = rs.wasNull() ? null : gi;
                return new Match(
                        rs.getDouble("carbs_100g"),
                        rs.getDouble("fiber_100g"),
                        rs.getDouble("protein_100g"),
                        rs.getDouble("fat_100g"),
                        rs.getDouble("kcal_100g"),
                        giValue,
                        rs.getDouble("typical_serving_g"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Food catalog lookup failed for " + alias, e);
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) st.execute(stmt);
            }
        }
    }

    private static String read(String classpath) {
        try {
            return new ClassPathResource(classpath).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing " + classpath, e);
        }
    }

    public record Match(double carbs100g, double fiber100g, double protein100g, double fat100g,
                        double kcal100g, Double gi, double typicalServingG) {}
}
