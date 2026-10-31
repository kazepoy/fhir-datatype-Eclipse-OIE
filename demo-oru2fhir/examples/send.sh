#!/bin/sh
# Envoie un ORU^R01 au canal de la démo et affiche le Bundle FHIR qu'il répond.
#   ./examples/send.sh                                       l'exemple complet
#   ./examples/send.sh examples/oru-r01-minimal.er7          un autre message
# ORU_URL change l'adresse (par défaut http://localhost:8081/oru/).
set -eu
cd "$(dirname "$0")/.."
file="${1:-examples/oru-r01-labo.er7}"
url="${ORU_URL:-http://localhost:8081/oru/}"
# charset=UTF-8 : sans lui, l'écouteur HTTP lit le message en ISO-8859-1 (accents abîmés).
curl --silent --show-error --fail-with-body -X POST "$url" \
  -H 'Content-Type: text/plain; charset=UTF-8' --data-binary "@$file"
echo
