#!/bin/sh
# Met à jour l'adresse IP de la box chez DuckDNS toutes les 5 minutes (service « duckdns » de docker-compose.yml).
# Le token n'est jamais affiché dans les logs.

if [ -z "$DUCKDNS_SUBDOMAIN" ] || [ -z "$DUCKDNS_TOKEN" ]; then
    echo "DuckDNS : DUCKDNS_SUBDOMAIN ou DUCKDNS_TOKEN manquant dans .env, rien à faire."
    # On reste en vie pour ne pas redémarrer en boucle
    while true; do sleep 3600; done
fi

while true; do
    if wget -qO- "https://www.duckdns.org/update?domains=${DUCKDNS_SUBDOMAIN}&token=${DUCKDNS_TOKEN}&ip=" | grep -q OK; then
        echo "$(date '+%Y-%m-%d %H:%M') DuckDNS : IP à jour pour ${DUCKDNS_SUBDOMAIN}.duckdns.org"
    else
        echo "$(date '+%Y-%m-%d %H:%M') DuckDNS : échec de la mise à jour (sous-domaine ou token incorrect ?)"
    fi
    sleep 300
done
