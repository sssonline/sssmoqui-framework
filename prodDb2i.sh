#! /usr/bin/env bash
MEM=8192
RE='^[1-9][0-9]*$'
if [[ $1 =~ $RE && $1 -le 128 ]]; then
  let "MEM = $1 * 1024"
fi
# MoquiProductionConfDb2i.xml leaves the DB user/password blank; refuse to start without them
missing=""
for v in entity_ds_user entity_ds_password; do
  [ -z "${!v}" ] && missing="$missing $v"
done
if [ -n "$missing" ]; then echo "Refusing to start, environment variables not set:$missing"; exit 1; fi
#java -server -Djava.awt.headless=true -XX:-OmitStackTraceInFastThrow -XX:+UseG1GC -Xmx${MEM}m -jar moqui.war conf=conf/MoquiProductionConfDb2i.xml
#java -Djava.awt.headless=true -XX:-OmitStackTraceInFastThrow -XX:+UseG1GC -Xmx${MEM}m -jar moqui.war conf=conf/MoquiProductionConfDb2i.xml port=8081
java -Djava.awt.headless=true -XX:-OmitStackTraceInFastThrow -XX:+UseG1GC -Xmx${MEM}m -jar moqui.war conf=conf/MoquiProductionConfDb2i.xml
