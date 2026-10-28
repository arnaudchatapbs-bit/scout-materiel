package scout.materiel.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Petites aides JDBC : requêtes paramétrées, lecture ligne à ligne. */
public final class Tx {
    private final Connection connection;

    Tx(Connection connection) {
        this.connection = connection;
    }

    public <T> List<T> list(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        }
    }

    public <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> rows = list(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long count(String sql, Object... params) throws SQLException {
        return one(sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    public int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement st = prepare(sql, params)) {
            return st.executeUpdate();
        }
    }

    /** INSERT ; renvoie l'identifiant créé. */
    public long insert(String sql, Object... params) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(st, params);
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    /** « ?, ?, ? » pour une clause IN (au moins un élément, sinon « NULL » qui ne correspond à rien). */
    public static String placeholders(Collection<?> values) {
        return values.isEmpty() ? "NULL" : values.stream().map(v -> "?").collect(Collectors.joining(", "));
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement st = connection.prepareStatement(sql);
        bind(st, params);
        return st;
    }

    private static void bind(PreparedStatement st, Object... params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            if (param instanceof Collection) {
                for (Object value : (Collection<?>) param) {
                    st.setObject(index++, value);
                }
            } else if (param instanceof Boolean) {
                st.setInt(index++, (Boolean) param ? 1 : 0);
            } else {
                st.setObject(index++, param);
            }
        }
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Lecture d'un entier pouvant être NULL. */
    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
