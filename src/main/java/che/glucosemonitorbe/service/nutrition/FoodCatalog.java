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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only SQLite catalog of per-100 g macros and glycemic index. The file is created from the
 * classpath seed the first time it is missing, then opened so later requests cannot write it.
 *
 * <p>Lookups use an in-memory alias index that also registers English plurals of each stored
 * alias ({@code grape} → {@code grapes}, {@code tomato} → {@code tomatoes}). That is preferred
 * over SQL {@code LIKE}/{@code FTS}: substring search matches the wrong foods (grape juice,
 * grapeseed oil) and is unsafe for carb dosing.
 */
@Component
public class FoodCatalog {

    private static final Set<String> IRREGULAR_SAME = Set.of("fish", "sheep", "rice", "bread", "garlic");
    private static final Map<String, String> IRREGULAR_PLURAL = Map.of(
            "leaf", "leaves",
            "loaf", "loaves",
            "potato", "potatoes",
            "tomato", "tomatoes",
            "mango", "mangoes",
            "hero", "heroes"
    );

    private final Map<String, Match> byAlias;

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
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro")) {
                byAlias = loadIndex(connection);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not open the food catalog at " + db, e);
        }
    }

    /**
     * Per-100 g row for an exact alias, a generated plural/singular form, a "with"/"and"/comma
     * segment (vision often returns "barley with onions and meat"), the label's last word, or
     * that word's variants. Null when nothing matches.
     */
    public Match find(String label) {
        if (label == null || label.isBlank()) return null;
        String key = label.trim().toLowerCase(Locale.ROOT);
        Match hit = lookupVariants(key);
        if (hit != null) return hit;
        for (String part : key.split("\\s*(?:,|;|\\band\\b|\\bwith\\b)\\s*")) {
            String segment = part.trim();
            if (segment.isEmpty() || segment.equals(key)) continue;
            hit = lookupVariants(segment);
            if (hit != null) return hit;
        }
        int space = key.lastIndexOf(' ');
        if (space < 0 || space == key.length() - 1) return null;
        return lookupVariants(key.substring(space + 1));
    }

    private Match lookupVariants(String alias) {
        Match exact = byAlias.get(alias);
        if (exact != null) return exact;
        for (String form : singularForms(alias)) {
            Match m = byAlias.get(form);
            if (m != null) return m;
        }
        return null;
    }

    private static Map<String, Match> loadIndex(Connection connection) throws SQLException {
        String sql = """
                SELECT a.alias, n.carbs_100g, n.fiber_100g, n.protein_100g, n.fat_100g, n.kcal_100g,
                       g.gi, a.typical_serving_g
                FROM food_alias a
                JOIN food_nutrient n ON n.id = a.nutrient_id
                LEFT JOIN gi_reference g ON g.id = a.gi_id
                """;
        Map<String, Match> index = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String alias = rs.getString("alias").toLowerCase(Locale.ROOT).trim();
                double gi = rs.getDouble("gi");
                Double giValue = rs.wasNull() ? null : gi;
                Match match = new Match(
                        rs.getDouble("carbs_100g"),
                        rs.getDouble("fiber_100g"),
                        rs.getDouble("protein_100g"),
                        rs.getDouble("fat_100g"),
                        rs.getDouble("kcal_100g"),
                        giValue,
                        rs.getDouble("typical_serving_g"));
                index.put(alias, match);
                for (String plural : englishPlurals(alias)) {
                    index.putIfAbsent(plural, match);
                }
            }
        }
        return Map.copyOf(index);
    }

    /**
     * English plural candidates for a catalog alias (single token or last word of a phrase).
     * Empty when the alias already looks plural.
     */
    static List<String> englishPlurals(String alias) {
        if (alias == null || alias.isBlank()) return List.of();
        String key = alias.trim().toLowerCase(Locale.ROOT);
        int space = key.lastIndexOf(' ');
        if (space >= 0) {
            String head = key.substring(0, space + 1);
            List<String> out = new ArrayList<>();
            for (String p : englishPlurals(key.substring(space + 1))) {
                out.add(head + p);
            }
            return out;
        }
        if (alreadyPlural(key) || IRREGULAR_SAME.contains(key)) return List.of();
        String irregular = IRREGULAR_PLURAL.get(key);
        if (irregular != null) return List.of(irregular);

        int n = key.length();
        if (n >= 2 && key.endsWith("y") && isConsonant(key.charAt(n - 2))) {
            return List.of(key.substring(0, n - 1) + "ies");
        }
        if (key.endsWith("o") && n > 2) {
            return List.of(key + "es");
        }
        if (key.endsWith("s") || key.endsWith("x") || key.endsWith("z")
                || key.endsWith("ch") || key.endsWith("sh")) {
            return List.of(key + "es");
        }
        return List.of(key + "s");
    }

    /** Conservative English singular candidates (query → catalog). */
    static List<String> singularForms(String alias) {
        if (alias == null || alias.length() < 3) return List.of();
        List<String> forms = new ArrayList<>(3);
        if (alias.endsWith("ies") && alias.length() > 4) {
            forms.add(alias.substring(0, alias.length() - 3) + "y");
        }
        if (alias.endsWith("ves") && alias.length() > 4) {
            forms.add(alias.substring(0, alias.length() - 3) + "f");
            forms.add(alias.substring(0, alias.length() - 3) + "fe");
        }
        if (alias.endsWith("oes") && alias.length() > 4) {
            forms.add(alias.substring(0, alias.length() - 2));
        } else if (alias.endsWith("es") && alias.length() > 4
                && !alias.endsWith("sses") && !alias.endsWith("oes")) {
            forms.add(alias.substring(0, alias.length() - 2));
            forms.add(alias.substring(0, alias.length() - 1));
        } else if (alias.endsWith("s") && !alias.endsWith("ss")) {
            forms.add(alias.substring(0, alias.length() - 1));
        }
        return forms;
    }

    private static boolean alreadyPlural(String alias) {
        if (alias.endsWith("ies") || alias.endsWith("oes") || alias.endsWith("ves")) return true;
        if (alias.endsWith("sses") || alias.endsWith("ches") || alias.endsWith("shes")) return true;
        // olives, grapes, fries, peas, beans, chips, tarts — but not asparagus/hummus (…us/…is)
        return alias.endsWith("s") && !alias.endsWith("us") && !alias.endsWith("is") && !alias.endsWith("ss");
    }

    private static boolean isConsonant(char c) {
        return "bcdfghjklmnpqrstvwxz".indexOf(Character.toLowerCase(c)) >= 0;
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
