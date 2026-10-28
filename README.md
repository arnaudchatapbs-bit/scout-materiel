# Scout Matériel (Java + Angular)

Gestion du matériel d'un groupe scout : inventaire, demandes de prêt par les invités, réservation article par article par l'administrateur, notifications par e-mail.

Réécriture de [scout-materiel](https://github.com/arnaudchata-appli/scout-materiel) (Django), avec les mêmes fonctionnalités et une interface homogène : la partie administration utilise les mêmes cartes, listes, puces et badges que la sélection du matériel.

- **Backend** : Java 11, [Javalin](https://javalin.io) (Jetty), SQLite en JDBC, sans ORM — dossier `backend/`
- **Front-end** : Angular 22 (composants autonomes, signaux), CSS maison, PWA — dossier `frontend/`
- **Cible** : Raspberry Pi 1 (ARMv6) avec Java 11 Zulu — voir [DEPLOIEMENT.md](DEPLOIEMENT.md)

## Développement

Prérequis : JDK 11 ou plus, Maven 3.9, Node.js 22.22+ ou 24.

```bash
cp .env.example .env              # DEBUG=true, mails dans le journal
cd backend
mvn -q package -DskipTests
java -jar target/scout-materiel.jar creer-admin chef chef@exemple.fr
java -jar target/scout-materiel.jar import-nomenclature ../chemin/nomenclature.xlsx
java -jar target/scout-materiel.jar      # API sur http://127.0.0.1:8000
```

Dans un autre terminal :

```bash
cd frontend
npm install
npm start                          # http://localhost:4200 (proxy /api → 8000)
```

Tests du backend (disponibilité, import, permissions, comptes) :

```bash
mvn -f backend/pom.xml test
```

## Commandes

`java -jar scout-materiel.jar <commande>` (sur le Pi : `./scripts/scout-materiel.sh <commande>`) :

| Commande | Rôle |
|---|---|
| *(aucune)* | démarre le serveur |
| `creer-admin <identifiant> <e-mail>` | crée un administrateur (mot de passe demandé, ou `ADMIN_PASSWORD`) |
| `import-nomenclature <fichier.xlsx> [--dry-run] [--onglet X]` | importe ou met à jour l'inventaire |
| `reprise-django <db.sqlite3>` | reprend toutes les données de l'application Django (base vide) |
| `sauvegarder [--keep 30] [--email]` | sauvegarde compressée de la base |
| `restaurer <fichier.sqlite3.gz> [--oui]` | restaure une sauvegarde (application arrêtée) |
| `test-email <adresse>` | vérifie l'envoi SMTP |
| `calibrer-hachage` | propose `PASSWORD_ITERATIONS` pour la machine |
