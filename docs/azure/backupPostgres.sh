#!/bin/bash
# Long-term Aspen database backup: pg_dump to the db-backups blob container (INSTALL.md phase 21).
# Runs as root from aspen-db-backup.timer. Signs in with the VM's managed identity, so no storage secret.
set -euo pipefail
umask 077

STORAGE_ACCOUNT="${1:?usage: backupPostgres.sh <storage-account>}"
CONTAINER=db-backups

set -a; . /opt/aspen/aspen.env; set +a
export PGPASSWORD="$entity_ds_password" PGSSLMODE=verify-full PGSSLROOTCERT=/etc/ssl/certs/azure-postgres-root-ca.pem

workDir=$(mktemp -d)
trap 'rm -rf "$workDir"' EXIT
blobName="aspen-$(date -u +%Y-%m-%d_%H%M%S).dump"
dumpFile="$workDir/$blobName"

pg_dump -Fc -h "$entity_ds_host" -U "$entity_ds_user" -d "$entity_ds_database" -f "$dumpFile"
pg_restore -l "$dumpFile" > /dev/null

token=$(curl -fsS -m 30 -H Metadata:true \
  'http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://storage.azure.com/' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { echo "no managed identity token" >&2; exit 1; }

blobUrl="https://$STORAGE_ACCOUNT.blob.core.windows.net/$CONTAINER/$blobName"
curl -fsS -m 1800 -X PUT -T "$dumpFile" "$blobUrl" -K - <<CURLCONF
header = "Authorization: Bearer $token"
header = "x-ms-version: 2023-11-03"
header = "x-ms-blob-type: BlockBlob"
header = "x-ms-access-tier: Cool"
header = "If-None-Match: *"
CURLCONF

localSize=$(stat -c %s "$dumpFile")
remoteSize=$(curl -fsSI -m 30 "$blobUrl" -K - <<CURLCONF | tr -d '\r' | awk 'tolower($1)=="content-length:"{print $2}'
header = "Authorization: Bearer $token"
header = "x-ms-version: 2023-11-03"
CURLCONF
)
[ "$localSize" = "$remoteSize" ] || { echo "size mismatch: local $localSize, blob $remoteSize" >&2; exit 1; }
echo "uploaded $blobName ($localSize bytes)"
