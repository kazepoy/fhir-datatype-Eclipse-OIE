#!/bin/sh
# Import des canaux de channels/ (un <channel> par fichier) par l'API REST d'OIE, puis leur
# déploiement. Un canal déjà présent est laissé tel quel, sauf avec OIE_INIT_FORCE=true :
# une modification faite dans l'administration survit donc à un docker compose up.
# Marque aussi la première connexion du compte comme faite (OIE_SKIP_FIRST_LOGIN, true par
# défaut) : l'administration ne demande alors pas de changer le mot de passe.
set -eu

OIE_URL="${OIE_URL:-https://oie-app:8443}"
OIE_USER="${OIE_USER:-admin}"
OIE_PASSWORD="${OIE_PASSWORD:-admin}"
OIE_INIT_FORCE="${OIE_INIT_FORCE:-false}"
OIE_SKIP_FIRST_LOGIN="${OIE_SKIP_FIRST_LOGIN:-true}"
CHANNELS_DIR="${OIE_INIT_DIR:-/oie-init}/channels"
RESPONSE_FILE=/tmp/oie-response.txt

fail() {
  echo "  -> ERREUR : $*" >&2
  exit 1
}

# request MÉTHODE CHEMIN [FICHIER [TYPE]] : appel REST authentifié (corps XML par défaut) ;
# affiche le code HTTP.
request() {
  set -- "$1" "$2" "${3:-}" "${4:-application/xml}"
  if [ -n "$3" ]; then
    curl --insecure --noproxy '*' --silent --show-error --max-time 300 \
      --user "$OIE_USER:$OIE_PASSWORD" --header 'X-Requested-With: OpenAPI' \
      --header "Content-Type: $4" --data-binary "@$3" \
      --request "$1" --output "$RESPONSE_FILE" --write-out '%{http_code}' "$OIE_URL/api$2" || fail "appel impossible : $1 $2"
  else
    curl --insecure --noproxy '*' --silent --show-error --max-time 300 \
      --user "$OIE_USER:$OIE_PASSWORD" --header 'X-Requested-With: OpenAPI' \
      --request "$1" --output "$RESPONSE_FILE" --write-out '%{http_code}' "$OIE_URL/api$2" || fail "appel impossible : $1 $2"
  fi
}

# api MÉTHODE CHEMIN [FICHIER [TYPE]] : échoue hors HTTP 2xx et sur une réponse booléenne false.
api() {
  status=$(request "$@")
  case "$status" in
    2??) ;;
    *) fail "HTTP $status sur $1 $2 : $(cat "$RESPONSE_FILE")" ;;
  esac
  if grep -qE '<boolean>false</boolean>|"boolean" *: *false|^false$' "$RESPONSE_FILE"; then
    fail "OIE a refusé $1 $2"
  fi
}

xml_value() {
  sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$2" | head -n 1
}

echo "=== Import des canaux ($OIE_URL) ==="
count=0
for file in "$CHANNELS_DIR"/*.xml; do
  [ -f "$file" ] || continue
  id=$(xml_value id "$file")
  name=$(xml_value name "$file")
  [ -n "$id" ] || fail "<id> introuvable dans $file"
  # GET d'un canal absent : HTTP 204 sans corps.
  status=$(request GET "/channels/$id")
  if [ "$OIE_INIT_FORCE" != true ] && [ "$status" = 200 ] && [ -s "$RESPONSE_FILE" ]; then
    echo "Canal déjà présent : $name"
    continue
  fi
  echo "Canal : $name"
  api PUT "/channels/$id?override=true" "$file"
  echo "Déploiement : $name"
  api POST "/channels/$id/_deploy?returnErrors=true"
  # returnErrors=true : le moteur ne répond rien quand le déploiement passe.
  if [ -s "$RESPONSE_FILE" ]; then
    echo "  -> ATTENTION, le moteur a répondu : $(cat "$RESPONSE_FILE")" >&2
  fi
  count=$((count + 1))
done

# L'administration web (comme Swing) ouvre un assistant de première connexion, qui demande un
# nouveau mot de passe, tant que la préférence firstlogin du compte n'est pas false.
if [ "$OIE_SKIP_FIRST_LOGIN" = true ]; then
  request GET /users/current > /dev/null
  user_id=$(xml_value id "$RESPONSE_FILE")
  [ -n "$user_id" ] || fail "id du compte $OIE_USER introuvable"
  printf false > /tmp/firstlogin.txt
  api PUT "/users/$user_id/preferences/firstlogin" /tmp/firstlogin.txt text/plain
  echo "Première connexion de $OIE_USER marquée comme faite"
fi

echo "=== Terminé : $count canal(aux) importé(s) et déployé(s) ==="
