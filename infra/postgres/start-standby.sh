#!/usr/bin/env bash
set -euo pipefail

: "${REPLICATION_PASSWORD:?DB_REPLICATION_PASSWORD must be set when enabling the replica}"
[[ "$REPLICATION_PASSWORD" != *$'\n'* && "$REPLICATION_PASSWORD" != *$'\r'* ]] || {
    echo "Replication password must fit on one line" >&2
    exit 1
}
umask 077
export PGPASSFILE=/var/run/postgresql/standby.pgpass
escaped=${REPLICATION_PASSWORD//\\/\\\\}
printf 'postgres:5432:replication:notification_streamer:%s\n' "${escaped//:/\\:}" > "$PGPASSFILE"
unset REPLICATION_PASSWORD escaped

if [[ ! -s "$PGDATA/PG_VERSION" ]]; then
    mkdir -p "$(dirname "$PGDATA")"
    if [[ -d "$PGDATA" ]]; then
        rmdir "$PGDATA"
    fi
    seed=$(mktemp -d "${PGDATA}.seed.XXXXXXXX")
    trap 'rm -rf -- "$seed"' EXIT
    pg_basebackup --dbname="host=postgres port=5432 user=notification_streamer passfile=$PGPASSFILE" \
        --pgdata="$seed" --write-recovery-conf --checkpoint=fast --no-password
    mv -T "$seed" "$PGDATA"
    trap - EXIT
fi
if [[ ! -f "$PGDATA/standby.signal" ]]; then
    echo "Refusing to start a data directory without standby.signal" >&2
    exit 1
fi
exec docker-entrypoint.sh postgres
