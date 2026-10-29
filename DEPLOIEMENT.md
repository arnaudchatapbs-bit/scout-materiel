# Déployer Scout Matériel (Java) sur le Raspberry Pi

Ce guide installe l'application sur le **Raspberry Pi 1 modèle B** (ARMv6), accessible depuis Internet en HTTPS à une adresse du type `https://scouts-xxx.duckdns.org`.

Fonctionnement :

```
Téléphone ──HTTPS──► Box (ports 80/443) ──► Pi : Caddy ─┬─ fichiers du front-end Angular (app/frontend)
                                                      └─ /api/ ──► Java (service systemd, 127.0.0.1:8000) ──► data/db.sqlite3

GitHub ── GitHub Actions teste et construit le paquet ──► onglet « Releases » ── scripts/deploy.sh ──► Pi
```

**Pourquoi Java n'est pas dans Docker** : sur l'ARMv6 du Pi 1, la seule JVM récente est **Azul Zulu 11**, et il n'en existe pas d'image Docker. Elle est donc installée directement sur le Pi (dans `./jdk`), et l'application tourne comme un service systemd. Docker ne sert plus qu'à Caddy (HTTPS) et DuckDNS, comme dans la version Django.

**Pas de compilation native (Spring Native, GraalVM, Quarkus natif)** : elles ne savent produire que du x86-64 ou de l'ARM 64 bits, pas de l'ARM 32 bits. Si un jour le Pi 1 est remplacé par un Pi 3, 4 ou 5 avec un système 64 bits, ces options redeviennent possibles.

Le Pi 1 est lent : il **ne compile rien**, il télécharge le paquet tout prêt. Comptez une à deux minutes de démarrage de la JVM.

> ⚠️ **À vérifier sur le Pi lui-même** : l'application a été testée sur PC avec Java 11 et 21, mais pas encore sur un vrai Pi 1. La première chose à faire est l'étape 4 (`java -version` doit répondre), puis noter le temps de démarrage affiché dans le journal.

Les commandes se lancent **sur le Pi, dans le dossier du projet** (`~/scout-materiel-java`).

---

## 0. Si l'application Django tourne déjà sur ce Pi

Les deux versions utilisent les ports 80/443 : on arrête l'ancienne, on garde sa base pour la reprise des données (étape 6).

```bash
cd ~/scout-materiel
./scripts/backup.sh                      # dernière sauvegarde, par sécurité
docker compose down                      # arrête Django et son Caddy
cp data/db.sqlite3 ~/django-db.sqlite3   # copie pour la reprise
crontab -e                               # supprimez la ligne de backup.sh (la version Java sauvegarde toute seule)
```

Pour revenir à Django plus tard : `cd ~/scout-materiel && docker compose up -d` (après avoir arrêté la version Java, voir Dépannage).

## 1. Préparer le Pi

Raspberry Pi OS **Lite 32 bits** (la version 64 bits ne fonctionne pas sur un Pi 1). Connectez-vous en SSH, puis :

```bash
uname -m                 # doit afficher armv6l
free -h
docker --version && docker compose version
sudo apt install -y git curl
```

**Swap d'1 Go** (indispensable avec 256 ou 512 Mo de RAM) — inutile si elle existe déjà depuis la version Django (`swapon --show`) :

```bash
sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Docker sans `sudo` : `sudo usermod -aG docker $USER`, puis déconnexion/reconnexion.

## 2. Adresse fixe, box et DuckDNS

Identique à la version Django (rien à refaire si c'est déjà en place) :

1. Dans la box, réservez une IP pour le Pi (ex. `192.168.1.50`) et redirigez **TCP 80 et 443** vers elle.
2. Vérifiez l'absence de CGNAT (IP publique de la box = celle de https://ifconfig.me).
3. Créez un sous-domaine sur https://www.duckdns.org (ex. `scouts-xxx`) et notez le **token**.

## 3. Récupérer le projet

**Dépôt public** :

```bash
git clone https://github.com/arnaudchata-appli/scout-materiel-java.git ~/scout-materiel-java
```

**Dépôt privé** : il faut une clé de déploiement (lecture seule) pour le code, et un jeton pour télécharger les versions.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/scout_java_deploy -N ""
cat ~/.ssh/scout_java_deploy.pub    # à coller dans GitHub : dépôt → Settings → Deploy keys (sans « write access »)
cat >> ~/.ssh/config <<'EOF'
Host github-scout-java
  HostName github.com
  User git
  IdentityFile ~/.ssh/scout_java_deploy
EOF
git clone github-scout-java:arnaudchata-appli/scout-materiel-java.git ~/scout-materiel-java
```

Jeton : GitHub → **Settings → Developer settings → Fine-grained tokens** → accès au seul dépôt `scout-materiel-java`, permission **Contents : Read-only**. Il ira dans `GITHUB_TOKEN` du `.env`.

## 4. Configurer puis installer

```bash
cd ~/scout-materiel-java
cp .env.example .env
openssl rand -base64 48        # copiez le résultat dans SECRET_KEY
nano .env
```

| Variable | Valeur sur le Pi |
|---|---|
| `SECRET_KEY` | la chaîne générée ci-dessus |
| `DEBUG` | `false` |
| `SITE_DOMAIN` / `HTTPS` | vides/`false` pour le premier test, puis `scouts-xxx.duckdns.org` / `true` (**toujours ensemble**) |
| `SITE_URL` | pour le premier test : `http://192.168.1.50` (l'IP du Pi) ; ensuite vide |
| `GITHUB_TOKEN` | le jeton, seulement si le dépôt est privé |
| `JVM_MAX_MEMORY` | `128m` (Pi de 512 Mo) ou `96m` (256 Mo) |
| `DUCKDNS_SUBDOMAIN` / `DUCKDNS_TOKEN` | `scouts-xxx` (sans `.duckdns.org`) et le token |
| `EMAIL_BACKEND` | `smtp` |
| `EMAIL_HOST_USER` / `EMAIL_HOST_PASSWORD` | adresse Gmail et **mot de passe d'application** |
| `DEFAULT_FROM_EMAIL` | `Scout Matériel <adresse@gmail.com>` |
| `ADMIN_EMAILS` | adresse(s) qui reçoivent les demandes et les sauvegardes ; remplacées par celles saisies dans Gestion › Réglages |

Puis l'installation (Java 11, dernière version de l'application, service systemd) :

```bash
chmod +x scripts/*.sh
./scripts/install.sh
journalctl -u scout-materiel -f     # attendez « Scout Matériel démarré … en … ms », puis Ctrl+C
```

Si `java -version` échoue pendant l'installation (« Illegal instruction »), la JVM n'est pas compatible avec ce processeur : voir Dépannage.

## 5. Régler la vitesse de connexion

Vérifier un mot de passe avec le réglage par défaut prendrait plusieurs secondes sur un Pi 1 et bloquerait le site pendant ce temps.

```bash
./scripts/scout-materiel.sh calibrer-hachage
```

Copiez la ligne `PASSWORD_ITERATIONS=…` proposée dans `.env`, puis `sudo systemctl restart scout-materiel`. Les mots de passe existants sont ré-encodés automatiquement à la connexion suivante.

## 6. Données : reprise depuis Django, ou import Excel

**Reprise de l'application Django** (comptes, mots de passe, matériel, demandes — tout est conservé) :

```bash
sudo systemctl stop scout-materiel
./scripts/scout-materiel.sh reprise-django ~/django-db.sqlite3
sudo systemctl start scout-materiel
```

La reprise ne fonctionne que dans une base vide : si vous avez déjà créé un compte, supprimez `data/db.sqlite3` avant.

**Ou installation neuve** :

```bash
./scripts/scout-materiel.sh creer-admin chef votre-adresse@gmail.com
./scripts/scout-materiel.sh import-nomenclature nomenclature_24_01_B_vue_arborescente.xlsx
```

L'import peut aussi se faire depuis l'application : **Gestion → Import Excel** (avec simulation).

Vérifiez les e-mails : `./scripts/scout-materiel.sh test-email votre-adresse@gmail.com`

## 7. Premier démarrage sur le réseau local

```bash
docker compose up -d
```

Ouvrez **http://192.168.1.50** sur un téléphone connecté au Wi-Fi de la maison : la page de connexion doit s'afficher.

## 8. Passer en HTTPS sur Internet

1. Dans `.env` : `SITE_DOMAIN=scouts-xxx.duckdns.org`, `HTTPS=true`, `SITE_URL=` (vide).
2. Relancez :

```bash
sudo systemctl restart scout-materiel
docker compose --profile internet up -d
docker compose logs duckdns          # « DuckDNS : IP à jour »
docker compose logs -f caddy         # attendez « certificate obtained successfully »
```

3. Testez **https://scouts-xxx.duckdns.org** depuis un téléphone **en 4G**, puis « Ajouter à l'écran d'accueil ».

Invitez ensuite les chefs depuis **Gestion → Comptes → Nouveau compte**.

## 9. Sauvegardes

L'application sauvegarde la base **toute seule chaque nuit** (`BACKUP_TIME`, 3 h 30 par défaut) dans `data/backups/` et garde les 30 dernières. Le **dimanche**, la sauvegarde part aussi par e-mail aux destinataires de Gestion › Réglages (à défaut `ADMIN_EMAILS`) : c'est la copie hors du Pi. Pas de cron à configurer.

Sauvegarde immédiate : `./scripts/scout-materiel.sh sauvegarder` (ajoutez `--email` pour l'envoyer).

### Restaurer une sauvegarde

```bash
sudo systemctl stop scout-materiel
./scripts/scout-materiel.sh restaurer data/backups/db-20261027-033000.sqlite3.gz
sudo systemctl start scout-materiel
```

La base actuelle est d'abord copiée à côté (`db.sqlite3.avant-restauration-…`). Pour un fichier reçu par mail, copiez-le d'abord sur le Pi avec `scp`.

## 10. Mettre à jour l'application

Après chaque modification poussée sur GitHub :
1. Attendez que l'action **« Tests et paquet Raspberry Pi »** soit verte (onglet *Actions*, quelques minutes).
2. Sur le Pi :

```bash
cd ~/scout-materiel-java && ./scripts/deploy.sh
```

La version précédente reste dans `app.old/` : pour revenir en arrière, `rm -rf app && mv app.old app && sudo systemctl restart scout-materiel`.

## 11. Dépannage

| Problème | Piste |
|---|---|
| `Illegal instruction` au lancement de Java | La JVM n'est pas compatible ARMv6 : essayez une autre version Zulu 11 `aarch32hf` (variable `ZULU_URL` de `scripts/install.sh`, liste sur https://www.azul.com/downloads/?version=java-11-lts&os=linux&architecture=arm-32-bit-hf), après `rm -rf jdk` |
| Démarrage très long ou `OutOfMemoryError` | `free -h` (swap active ?), baissez ou montez `JVM_MAX_MEMORY` ; ne lancez rien d'autre de lourd |
| Erreur 502 juste après un redémarrage | La JVM démarre encore : `journalctl -u scout-materiel -f` |
| `SITE_DOMAIN et HTTPS vont ensemble` | Mettez `HTTPS=true` avec un domaine, et videz `SITE_URL` |
| Pas de certificat HTTPS | Ports 80/443 non redirigés, CGNAT, ou DuckDNS (`docker compose logs caddy duckdns`) |
| `Aucune version publiée trouvée` | L'action GitHub n'a pas encore réussi, ou `GITHUB_TOKEN` manque (dépôt privé) |
| Mails non reçus | `./scripts/scout-materiel.sh test-email adresse@gmail.com` et le mot de passe d'application Gmail |
| Revenir à la version Django | `sudo systemctl disable --now scout-materiel && docker compose down`, puis `cd ~/scout-materiel && docker compose up -d` |

État et journaux :

```bash
systemctl status scout-materiel
journalctl -u scout-materiel --since "1 hour ago"
docker compose ps
```
