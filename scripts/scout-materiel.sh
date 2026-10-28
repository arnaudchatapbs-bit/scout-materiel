#!/bin/sh
# Lance Scout Matériel avec la JVM et les réglages adaptés au Raspberry Pi 1.
#   ./scripts/scout-materiel.sh                      → serveur (c'est ce que lance systemd)
#   ./scripts/scout-materiel.sh <commande> [args]    → commande d'exploitation, ex. :
#       ./scripts/scout-materiel.sh creer-admin chef chef@exemple.fr
#       ./scripts/scout-materiel.sh import-nomenclature nomenclature.xlsx
#       ./scripts/scout-materiel.sh test-email adresse@gmail.com
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"

# Le .env n'est pas un script shell (« < > » de DEFAULT_FROM_EMAIL, espaces du mot de passe
# Gmail) : Java le lit lui-même, on n'en extrait ici que la mémoire de la JVM.
if [ -z "$JVM_MAX_MEMORY" ] && [ -f .env ]; then
    JVM_MAX_MEMORY="$(sed -n 's/^JVM_MAX_MEMORY=//p' .env | tr -d '"' | tr -d "'" | tr -d ' \r')"
fi
export DATABASE_PATH="${DATABASE_PATH:-$DIR/data/db.sqlite3}"
export BACKUP_DIR="${BACKUP_DIR:-$DIR/data/backups}"

JAVA="$DIR/jdk/bin/java"
if [ ! -x "$JAVA" ]; then
    JAVA="java"
fi

# Pi 1 : un cœur, 256 à 512 Mo de RAM.
#   -Xmx           mémoire maximale de la JVM (JVM_MAX_MEMORY dans .env, défaut 128m)
#   SerialGC       ramasse-miettes le plus léger, adapté à un seul cœur
#   Xss            piles de threads plus petites (peu de threads, peu de récursion)
#   Xshare:auto    réutilise l'archive de classes partagées du JDK si elle existe (démarrage plus rapide)
exec "$JAVA" \
    -Xms32m -Xmx"${JVM_MAX_MEMORY:-128m}" -Xss512k \
    -XX:+UseSerialGC -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=96m \
    -Xshare:auto -Dfile.encoding=UTF-8 \
    -jar "$DIR/app/scout-materiel.jar" "$@"
