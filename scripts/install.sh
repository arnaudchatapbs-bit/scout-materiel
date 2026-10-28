#!/bin/sh
# Première installation sur le Raspberry Pi (voir DEPLOIEMENT.md, étape 4).
#   1. installe Java 11 (Azul Zulu, seule version récente qui tourne sur l'ARMv6 du Pi 1) dans ./jdk
#   2. télécharge la dernière version de l'application (scripts/deploy.sh)
#   3. installe et active le service systemd « scout-materiel »
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"

# Zulu 11 pour ARM 32 bits « hard float » (Raspberry Pi OS 32 bits). Zulu 17 et plus exigent ARMv7.
ZULU_URL="${ZULU_URL:-https://cdn.azul.com/zulu/bin/zulu11.90.205-ca-jdk11.0.32.1-linux_aarch32hf.tar.gz}"

if [ ! -f .env ]; then
    echo "Créez d'abord le fichier .env : cp .env.example .env && nano .env (voir DEPLOIEMENT.md)"
    exit 1
fi

case "$(uname -m)" in
    armv6l|armv7l) ;;
    *) echo "Attention : processeur $(uname -m), ce script installe Java pour ARM 32 bits." ;;
esac

if [ -x jdk/bin/java ]; then
    echo "→ Java déjà installé : $(jdk/bin/java -version 2>&1 | head -1)"
else
    echo "→ Téléchargement de Java 11 (Zulu, ~180 Mo, plusieurs minutes sur un Pi 1)"
    curl -fL --retry 3 -o /tmp/zulu.tar.gz "$ZULU_URL"
    rm -rf jdk.tmp && mkdir jdk.tmp
    tar -xzf /tmp/zulu.tar.gz -C jdk.tmp --strip-components=1
    rm /tmp/zulu.tar.gz
    mv jdk.tmp jdk
    echo "→ Vérification : $(jdk/bin/java -version 2>&1 | head -1)"
    # Archive de classes partagées : démarrages suivants plus rapides
    jdk/bin/java -Xshare:dump > /dev/null 2>&1 || true
fi

mkdir -p data/backups
chmod +x scripts/*.sh

echo "→ Téléchargement de l'application"
./scripts/deploy.sh --no-restart

echo "→ Installation du service systemd"
sed -e "s|__USER__|$(id -un)|g" -e "s|__DIR__|$DIR|g" deploy/scout-materiel.service \
    | sudo tee /etc/systemd/system/scout-materiel.service > /dev/null
sudo systemctl daemon-reload
sudo systemctl enable --now scout-materiel
echo "Service démarré. Suivre le démarrage : journalctl -u scout-materiel -f"
