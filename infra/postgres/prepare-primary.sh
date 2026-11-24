#!/usr/bin/env bash
set -euo pipefail

: "${REPLICATION_PASSWORD:?DB_REPLICATION_PASSWORD must be set when enabling the replica}"
[[ "$REPLICATION_PASSWORD" != *$'\n'* && "$REPLICATION_PASSWORD" != *$'\r'* ]] || {
    echo "Replication password must fit on one line" >&2
    exit 1
}
export PGUSER="$POSTGRES_USER" PGPASSWORD="$POSTGRES_PASSWORD" PGDATABASE="$POSTGRES_DB"
psql=(psql -X -w -q --set=ON_ERROR_STOP=1)
"${psql[@]}" <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'notification_streamer') THEN
        CREATE ROLE notification_streamer WITH LOGIN REPLICATION;
    ELSIF NOT EXISTS (
        SELECT FROM pg_roles WHERE rolname = 'notification_streamer'
        AND rolcanlogin AND rolreplication AND NOT rolsuper AND NOT rolcreatedb
        AND NOT rolcreaterole AND NOT rolbypassrls
    ) THEN
        RAISE EXCEPTION 'notification_streamer already exists with incompatible privileges';
    END IF;
END
$$;
SQL
printf '%s\n%s\n' "$REPLICATION_PASSWORD" "$REPLICATION_PASSWORD" |
    PGOPTIONS='-c password_encryption=scram-sha-256' "${psql[@]}" -c '\password notification_streamer'

hba=$("${psql[@]}" -Atc 'SHOW hba_file')
rule='host replication notification_streamer samenet scram-sha-256'
if ! grep -Fxq "$rule" "$hba"; then
    printf '\n%s\n' "$rule" >> "$hba"
fi
"${psql[@]}" -c 'SELECT pg_reload_conf()'
