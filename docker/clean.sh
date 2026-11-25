#! /bin/bash

rm -Rf runtime/
rm -Rf runtime1/
rm -Rf runtime2/
rm -Rf db/

docker rm moqui-server
docker rm moqui-database
docker rm nginx-proxy
