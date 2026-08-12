#!/usr/bin/env bash
set -euo pipefail

KCADM=/opt/keycloak/bin/kcadm.sh
SERVER="${KEYCLOAK_SERVER:-http://keycloak:8080}"
ADMIN_REALM="${KEYCLOAK_ADMIN_REALM:-master}"
ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="${KEYCLOAK_REALM:-infinity-knowledge}"
CONFIG_DIR=/config

until "$KCADM" config credentials \
  --server "$SERVER" \
  --realm "$ADMIN_REALM" \
  --user "$ADMIN_USER" \
  --password "$ADMIN_PASSWORD" >/dev/null 2>&1; do
  sleep 2
done

until "$KCADM" get "realms/$REALM" >/dev/null 2>&1; do
  sleep 2
done

upsert_role() {
  local role_name="$1"
  local definition="$2"
  if "$KCADM" get "roles/$role_name" -r "$REALM" >/dev/null 2>&1; then
    "$KCADM" update "roles/$role_name" -r "$REALM" -f "$definition" >/dev/null
  else
    "$KCADM" create roles -r "$REALM" -f "$definition" >/dev/null
  fi
}

upsert_client() {
  local client_id="$1"
  local definition="$2"
  local client_uuid
  client_uuid=$("$KCADM" get clients \
    -r "$REALM" \
    -q "clientId=$client_id" \
    --fields id \
    --format csv \
    --noquotes | tail -n 1)

  if [[ -n "$client_uuid" ]]; then
    "$KCADM" update "clients/$client_uuid" -r "$REALM" -f "$definition" >/dev/null
  else
    "$KCADM" create clients -r "$REALM" -f "$definition" >/dev/null
    client_uuid=$("$KCADM" get clients \
      -r "$REALM" \
      -q "clientId=$client_id" \
      --fields id \
      --format csv \
      --noquotes | tail -n 1)
  fi
  printf '%s' "$client_uuid"
}

upsert_mapper() {
  local client_uuid="$1"
  local mapper_name="$2"
  local definition="$3"
  local mapper_id=""
  local candidate_id
  local candidate_name
  while IFS=, read -r candidate_id candidate_name; do
    if [[ "$candidate_name" == "$mapper_name" ]]; then
      mapper_id="$candidate_id"
      break
    fi
  done < <("$KCADM" get "clients/$client_uuid/protocol-mappers/models" \
    -r "$REALM" \
    --fields id,name \
    --format csv \
    --noquotes)

  if [[ -n "$mapper_id" ]]; then
    "$KCADM" update \
      "clients/$client_uuid/protocol-mappers/models/$mapper_id" \
      -r "$REALM" \
      -f "$definition" \
      --merge >/dev/null
  else
    "$KCADM" create "clients/$client_uuid/protocol-mappers/models" \
      -r "$REALM" \
      -f "$definition" >/dev/null
  fi
}

upsert_user() {
  local username="$1"
  local password="$2"
  local definition="$3"
  local user_id
  user_id=$("$KCADM" get users \
    -r "$REALM" \
    -q "username=$username" \
    --fields id \
    --format csv \
    --noquotes | tail -n 1)

  if [[ -n "$user_id" ]]; then
    "$KCADM" update "users/$user_id" -r "$REALM" -f "$definition" >/dev/null
  else
    "$KCADM" create users -r "$REALM" -f "$definition" >/dev/null
    user_id=$("$KCADM" get users \
      -r "$REALM" \
      -q "username=$username" \
      --fields id \
      --format csv \
      --noquotes | tail -n 1)
  fi
  "$KCADM" set-password \
    -r "$REALM" \
    --username "$username" \
    --new-password "$password" >/dev/null
  printf '%s' "$user_id"
}

has_realm_role() {
  local user_id="$1"
  local role_name="$2"
  "$KCADM" get "users/$user_id/role-mappings/realm" \
    -r "$REALM" \
    --fields name \
    --format csv \
    --noquotes |
    grep -Fxq "$role_name"
}

ensure_realm_role() {
  local user_id="$1"
  local username="$2"
  local role_name="$3"
  if ! has_realm_role "$user_id" "$role_name"; then
    "$KCADM" add-roles \
      -r "$REALM" \
      --uusername "$username" \
      --rolename "$role_name" >/dev/null
  fi
}

remove_realm_role() {
  local user_id="$1"
  local username="$2"
  local role_name="$3"
  if has_realm_role "$user_id" "$role_name"; then
    "$KCADM" remove-roles \
      -r "$REALM" \
      --uusername "$username" \
      --rolename "$role_name" >/dev/null
  fi
}

upsert_role knowledge-reader "$CONFIG_DIR/knowledge-reader-role.json"
upsert_role knowledge-admin "$CONFIG_DIR/knowledge-admin-role.json"

console_uuid=$(upsert_client \
  infinity-knowledge-console \
  "$CONFIG_DIR/infinity-knowledge-console-client.json")
cli_uuid=$(upsert_client \
  infinity-knowledge-cli \
  "$CONFIG_DIR/infinity-knowledge-cli-client.json")
api_uuid=$(upsert_client \
  infinity-knowledge-api \
  "$CONFIG_DIR/infinity-knowledge-api-client.json")

for client_uuid in "$console_uuid" "$cli_uuid"; do
  upsert_mapper "$client_uuid" tenant-id "$CONFIG_DIR/tenant-id-mapper.json"
  upsert_mapper "$client_uuid" departments "$CONFIG_DIR/departments-mapper.json"
  upsert_mapper "$client_uuid" api-audience "$CONFIG_DIR/api-audience-mapper.json"
done

reader_id=$(upsert_user \
  demo-reader \
  demo-reader \
  "$CONFIG_DIR/demo-reader-user.json")
admin_id=$(upsert_user \
  demo-admin \
  demo-admin \
  "$CONFIG_DIR/demo-admin-user.json")

ensure_realm_role "$reader_id" demo-reader knowledge-reader
remove_realm_role "$reader_id" demo-reader knowledge-admin
ensure_realm_role "$admin_id" demo-admin knowledge-reader
ensure_realm_role "$admin_id" demo-admin knowledge-admin
