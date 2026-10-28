package scout.materiel.accounts;

import scout.materiel.common.ApiError;

/**
 * Rôles de l'application : admin = compte actif avec le drapeau is_admin, invité = tout autre compte actif.
 * isAdmin() est l'unique source de vérité : ne pas tester le drapeau ailleurs.
 */
public final class Roles {
    private Roles() {
    }

    public static boolean isAdmin(User user) {
        return user != null && user.active && user.adminFlag;
    }

    public static void requireAdmin(User user) {
        if (!isAdmin(user)) {
            throw ApiError.forbidden();
        }
    }
}
