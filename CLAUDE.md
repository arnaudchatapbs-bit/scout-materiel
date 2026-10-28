# Scout Matériel (Java + Angular)

Réécriture de l'application Django `scout-materiel` (même fonctionnement, mêmes règles métier) : inventaire du matériel d'un groupe scout, demandes de prêt par les invités, réservation article par article par l'admin, e-mails.

Langue : toute l'interface, les messages d'erreur de l'API et les e-mails sont **en français**. Le code (classes, variables) est en anglais, les commentaires en français. Les chemins d'URL et d'API sont en français (`/materiel`, `/api/panier`…).

## Contrainte principale : Raspberry Pi 1 (ARMv6)

- Seule JVM récente disponible : **Azul Zulu 11** → tout le backend reste **compatible Java 11** (`maven.compiler.release=11` ; javac refuse une dépendance compilée pour Java 17+). Pas de `record`, `switch` en flèche, blocs de texte, `instanceof` avec motif.
- **Pas de natif** (GraalVM, Spring Native, Quarkus natif) : aucun ne cible l'ARM 32 bits. Pas de Spring/Hibernate : trop lourds au démarrage sur un cœur ARMv6 à 700 MHz.
- Java n'est pas dans Docker (pas d'image JVM ARMv6) : service systemd + Zulu dans `./jdk` sur le Pi ; Docker ne sert qu'à Caddy et DuckDNS. Voir `DEPLOIEMENT.md`.
- Toute nouvelle dépendance : vérifier qu'elle vise Java 11 au plus, et qu'elle n'embarque pas de code natif sans version `Linux/armv6` (sqlite-jdbc l'a).

## Stack

- `backend/` : Java 11, **Javalin 6** (Jetty 11), **SQLite** via JDBC (`db/Database` : une connexion partagée sous verrou, transactions IMMEDIATE, WAL), Jackson, Angus Mail, fastexcel-reader. Pas d'injection de dépendances : `Services` assemble tout.
- `frontend/` : **Angular 22**, composants autonomes, signaux, sans zone ; CSS maison dans `src/styles.css` (pas de framework CSS) ; PWA minimale (`public/sw.js`, réseau d'abord, incrémenter `CACHE` à chaque modification).
- Construction par GitHub Actions (`.github/workflows/ci.yml`, tests sous **Java 11**) → archive `scout-materiel-pi.tar.gz` dans les Releases → `scripts/deploy.sh` sur le Pi.

## Interface homogène (demande explicite de l'utilisateur)

La partie admin (Gestion) doit ressembler à la sélection du matériel. Toujours réutiliser les briques de `styles.css` et `shared/ui.ts` plutôt que créer un style de page :
- `.cards`/`.card` (accueils), `.list`/`.row` (toutes les listes : objets, demandes, comptes, catégories), `.chips`/`.chip` (sous-catégories, onglets, filtres), `.badge-*`, `.panel` (fiches, formulaires), `.btn`, `.round` (« + », « ✎ », « × » en bout de ligne).
- `app-item-row` est la seule ligne d'objet (inventaire, panier, gestion) ; les boutons à droite sont projetés.
- Un admin voit les mêmes pages qu'un invité, avec en plus « ✎ Modifier » et « + Nouvel objet ».

## Rôles et sécurité

- Admin = compte actif avec `is_admin`. **Toujours tester via `Roles.isAdmin(user)`** (backend) ou `auth.isAdmin()` (front), jamais le drapeau.
- Toute route `/api/*` exige une session, sauf `WebApp.PUBLIC` ; `/api/gestion/*` est réservé à l'admin (vérifié dans `WebApp.authenticate`). Le garde Angular n'est qu'un confort.
- Session : cookie `sm_session` HttpOnly SameSite=Lax (90 jours, jeton haché en base). Requêtes non-GET : en-tête `X-Requested-With` obligatoire (ajouté par `csrfInterceptor`).
- Comptes créés par l'admin, sans mot de passe : lien d'invitation signé (HMAC + hash du mot de passe → usage unique, 7 jours).
- Mots de passe : PBKDF2-SHA256 **au format Django** (compatible avec la reprise), 12 caractères minimum, itérations réglables (`PASSWORD_ITERATIONS`, commande `calibrer-hachage`).

## Règles métier (identiques à la version Django)

- **Disponible = quantité − lignes `reservee`** (`loans/Availability`). Les lignes `en_attente` ne bloquent rien ; seul l'admin les voit.
- `inventory/Visibility.visibleItems` est la **seule source** de ce qu'un utilisateur voit : un objet entièrement réservé est invisible pour les invités (404 sur la fiche), toujours visible pour l'admin.
- Consommables visibles mais non réservables (drapeau sur l'objet ou une catégorie ancêtre).
- Demande sans dates ni message ; statut déduit des lignes ; chaque ligne a son statut (`en_attente`, `reservee`, `refusee`, `annulee`, `rendue`).
- `LoanService` : `submitCart` revérifie la disponibilité ; `applyDecisions` est tout-ou-rien ; `cancelRequest` annule les lignes en attente de l'invité.
- E-mails après enregistrement, jamais bloquants (`Mailer.send` renvoie false) : nouvelle demande → admin + confirmation invité ; annulation → admin ; **aucun mail pour les décisions de l'admin**.
- Panier stocké en base (`cart_items`), pas en session.
- Import Excel idempotent (`inventory/NomenclatureImporter`), mêmes règles que la version Django (lignes « X »/« Emplacement libre » ignorées, doublons sans référence fusionnés, « Alimentation » et « Consommables » consommables).

## Conventions

- Schéma : migrations SQL numérotées dans `backend/src/main/resources/db/`, appliquées au démarrage (`PRAGMA user_version`). Ne jamais modifier une migration publiée : en ajouter une.
- Erreurs : lever `ApiError` (messages en français) → JSON `{"errors": [...]}` ; dans une transaction, elle annule tout.
- Tests : `mvn -f backend/pom.xml test` (JUnit 5, base en mémoire via `Fixtures`). Priorité : disponibilité, import, permissions.
- Maven local : si `~/.m2/settings.xml` pointe vers un Nexus injoignable, utiliser `-s` avec un fichier de réglages vide.
- Front : `npm start` dans `frontend/` (proxy `/api` → 8000) ; `npx ng build` doit passer sans avertissement.
- Toute page doit être utilisable sur un écran de 360 px de large.
