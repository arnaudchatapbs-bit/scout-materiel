package scout.materiel.accounts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.sql.ResultSet;
import java.sql.SQLException;

public class User {
    public long id;
    public String username;
    public String email;
    public String firstName;
    public String lastName;
    @JsonIgnore
    public String passwordHash;
    /** Lire le rôle via Roles.isAdmin(), jamais ce champ directement. */
    @JsonIgnore
    public boolean adminFlag;
    public boolean active;
    public String dateJoined;
    public String lastLogin;

    public static final String COLUMNS =
            "u.id, u.username, u.email, u.first_name, u.last_name, u.password_hash, u.is_admin, u.is_active, u.date_joined, u.last_login";

    public static User map(ResultSet rs) throws SQLException {
        User user = new User();
        user.id = rs.getLong("id");
        user.username = rs.getString("username");
        user.email = rs.getString("email");
        user.firstName = rs.getString("first_name");
        user.lastName = rs.getString("last_name");
        user.passwordHash = rs.getString("password_hash");
        user.adminFlag = rs.getInt("is_admin") == 1;
        user.active = rs.getInt("is_active") == 1;
        user.dateJoined = rs.getString("date_joined");
        user.lastLogin = rs.getString("last_login");
        return user;
    }

    /** « Prénom Nom », sinon l'identifiant. */
    public String displayName() {
        String full = (firstName + " " + lastName).trim();
        return full.isEmpty() ? username : full;
    }

    public boolean isAdmin() {
        return Roles.isAdmin(this);
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isEmpty();
    }
}
