#!/bin/bash
# Daily backup watchdog (INSTALL.md phase 21): reports hours since the newest blob in db-backups as the
# custom metric Aspen/BackupAgeHours on this VM. An Azure Monitor alert fires when it gets too old.
# Runs as root from aspen-backup-check.timer, signed in with the VM's managed identity.
set -euo pipefail

STORAGE_ACCOUNT="${1:?usage: checkBackupAge.sh <storage-account>}"
CONTAINER=db-backups
imds=http://169.254.169.254/metadata

tokenFor() {
  curl -fsS -m 30 -H Metadata:true "$imds/identity/oauth2/token?api-version=2018-02-01&resource=$1" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

storageToken=$(tokenFor https://storage.azure.com/)
newest=$(curl -fsS -m 60 "https://$STORAGE_ACCOUNT.blob.core.windows.net/$CONTAINER?restype=container&comp=list&prefix=aspen-" -K - <<CURLCONF | grep -o '<Last-Modified>[^<]*' | sed 's/<Last-Modified>//' | while read -r d; do date -u -d "$d" +%s; done | sort -n | tail -n 1
header = "Authorization: Bearer $storageToken"
header = "x-ms-version: 2023-11-03"
CURLCONF
)
# no backup at all counts as very old
ageHours=$(( ( $(date -u +%s) - ${newest:-0} ) / 3600 ))

resourceId=$(curl -fsS -m 30 -H Metadata:true "$imds/instance/compute/resourceId?api-version=2021-02-01&format=text")
location=$(curl -fsS -m 30 -H Metadata:true "$imds/instance/compute/location?api-version=2021-02-01&format=text")
monitorToken=$(tokenFor https://monitoring.azure.com/)

body='{"time":"'$(date -u +%Y-%m-%dT%H:%M:%SZ)'","data":{"baseData":{"metric":"BackupAgeHours","namespace":"Aspen","series":[{"min":'$ageHours',"max":'$ageHours',"sum":'$ageHours',"count":1}]}}}'
curl -fsS -m 60 -o /dev/null -X POST "https://$location.monitoring.azure.com$resourceId/metrics" \
  -H 'Content-Type: application/json' -d "$body" -K - <<CURLCONF
header = "Authorization: Bearer $monitorToken"
CURLCONF
echo "newest backup is $ageHours hours old"
