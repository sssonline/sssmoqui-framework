#! /usr/bin/env bash
MEM=8192
RE='^[1-9][0-9]*$'
if [[ $1 =~ $RE && $1 -le 128 ]]; then
  let "MEM = $1 * 1024"
fi
# MoquiProductionConfPostgres.xml leaves these blank; refuse to start without them (a missing crypt pass would
# otherwise only fail at the first encrypt/decrypt, long after startup)
missing=""
for v in entity_ds_host entity_ds_user entity_ds_password entity_ds_crypt_pass; do
  [ -z "${!v}" ] && missing="$missing $v"
done
if [ -n "$missing" ]; then echo "Refusing to start, environment variables not set:$missing"; exit 1; fi
rootCert="${entity_ds_ssl_root_cert:-/opt/aspen/shared/conf/azure-postgres-root-ca.pem}"
if [ ! -r "$rootCert" ]; then echo "Refusing to start, Postgres TLS root CA bundle not readable: $rootCert"; exit 1; fi
# the Bitronix server ID is generated on first start and saved in runtime/txlog/server-id; ASPEN_TM_SERVER_ID only overrides it
TM_OPT=""; [ -n "$ASPEN_TM_SERVER_ID" ] && TM_OPT="-Dbitronix.tm.serverId=$ASPEN_TM_SERVER_ID"
java -server -Djava.awt.headless=true -XX:-OmitStackTraceInFastThrow -XX:+UseG1GC -Xmx${MEM}m $TM_OPT -jar moqui.war conf=conf/MoquiProductionConfPostgres.xml
