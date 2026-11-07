#!/bin/sh
# Mise à jour de Scout Matériel sur le Pi : scripts à jour, dernière version construite par
# GitHub Actions (onglet Releases du dépôt), redémarrage du service.
# Usage (sur le Pi, dans le dossier du projet) : ./scripts/deploy.sh
#
# Tout le script est entre accolades : sh le lit en entier avant de l'exécuter, ce qui évite
# un comportement imprévisible quand « git pull » modifie ce fichier pendant qu'il tourne.
{
set -e
cd "$(dirname "$0")/.."
REPO="${GITHUB_REPO:-arnaudchatapbs-bit/scout-materiel}"
ASSET="scout-materiel-pi.tar.gz"

# Jeton GitHub (lecture seule) nécessaire si le dépôt est privé : GITHUB_TOKEN dans .env
TOKEN="$(sed -n 's/^GITHUB_TOKEN=//p' .env 2>/dev/null | tr -d '"' | tr -d "'")"
AUTH=""
if [ -n "$TOKEN" ]; then
    AUTH="Authorization: Bearer $TOKEN"
fi

if [ -d .git ]; then
    echo "→ Récupération des scripts"
    git pull --ff-only
fi

echo "→ Recherche de la dernière version"
RELEASE="$(curl -fsSL --retry 3 ${AUTH:+-H "$AUTH"} "https://api.github.com/repos/$REPO/releases/latest")"
TAG="$(printf '%s' "$RELEASE" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1)"
# L'API renvoie un champ par ligne : on recolle tout avant de couper aux virgules, sinon
# « url » se retrouve hors de portée de grep -B3 (url, id, node_id précèdent name)
ASSET_URL="$(printf '%s' "$RELEASE" | tr -d '\n' | tr ',' '\n' | grep -B3 "\"name\": *\"$ASSET\"" | sed -n 's/.*"url": *"\([^"]*\)".*/\1/p' | tail -1)"
if [ -z "$ASSET_URL" ]; then
    echo "Aucune version publiée trouvée : vérifiez l'onglet Actions du dépôt (et GITHUB_TOKEN si le dépôt est privé)."
    exit 1
fi
if [ -f app/VERSION ] && [ "$(cat app/VERSION)" = "$TAG" ]; then
    echo "Déjà à jour ($TAG)."
else
    echo "→ Téléchargement de $TAG"
    rm -rf app.new && mkdir app.new
    curl -fL --retry 3 ${AUTH:+-H "$AUTH"} -H "Accept: application/octet-stream" "$ASSET_URL" -o /tmp/$ASSET
    tar -xzf /tmp/$ASSET -C app.new
    rm /tmp/$ASSET
    echo "$TAG" > app.new/VERSION
    # Bascule : l'ancienne version reste dans app.old en cas de besoin
    rm -rf app.old
    if [ -d app ]; then mv app app.old; fi
    mv app.new app
fi

if [ "$1" != "--no-restart" ]; then
    echo "→ Redémarrage (une à deux minutes sur un Pi 1)"
    sudo systemctl restart scout-materiel
    PROFILE=""
    if grep -q '^DUCKDNS_TOKEN=..*' .env 2>/dev/null; then
        PROFILE="--profile internet"
    fi
    # shellcheck disable=SC2086  # PROFILE est vide ou contient deux mots, volontairement non entre guillemets
    docker compose $PROFILE up -d
    # Caddy doit être recréé : son volume ./app/frontend reste attaché au dossier d'origine, devenu
    # app.old puis supprimé au déploiement suivant (le site répondait alors 404 partout)
    docker compose up -d --force-recreate --no-deps caddy
    systemctl --no-pager status scout-materiel | head -5
fi
exit 0
}
