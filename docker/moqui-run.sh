#! /bin/bash

echo "Usage: moqui-run.sh [<moqui directory like ..>] [<group/name:tag>] [<runtime image like eclipse-temurin:11-jdk>]"
echo

MOQUI_HOME="${1:-..}"
NAME_TAG="${2:-moqui}"
RUNTIME_IMAGE="${3:-eclipse-temurin:11-jdk}"

if [ -f simple/docker-build.sh ]; then
  cd simple
  ./docker-build.sh ../.. $NAME_TAG $RUNTIME_IMAGE
  # shellcheck disable=SC2103
  cd ..
fi

if [ ! -e runtime ]; then mkdir runtime; fi
if [ ! -e runtime/conf ]; then cp -R $MOQUI_HOME/runtime/conf runtime/; fi
if [ ! -e runtime/lib ]; then cp -R $MOQUI_HOME/runtime/lib runtime/; fi
if [ ! -e runtime/classes ]; then cp -R $MOQUI_HOME/runtime/classes runtime/; fi
if [ ! -e runtime/log ]; then cp -R $MOQUI_HOME/runtime/log runtime/; fi
if [ ! -e runtime/txlog ]; then cp -R $MOQUI_HOME/runtime/txlog runtime/; fi
if [ ! -e runtime/db ]; then cp -R $MOQUI_HOME/runtime/db runtime/; fi

docker run --rm -p 80:80 -v $PWD/runtime/conf:/opt/moqui/runtime/conf -v $PWD/runtime/lib:/opt/moqui/runtime/lib \
    -v $PWD/runtime/classes:/opt/moqui/runtime/classes -v $PWD/runtime/log:/opt/moqui/runtime/log \
    -v $PWD/runtime/txlog:/opt/moqui/runtime/txlog -v $PWD/runtime/db:/opt/moqui/runtime/db \
    --name moqui-server $NAME_TAG
# docker run -d -p 80:80 $NAME_TAG
# docker run --rm -p 80:80 $NAME_TAG
