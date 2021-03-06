#!/usr/bin/env bash
#
# The MIT License
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
# THE SOFTWARE.
#

set -e

DEFAULT_DOCKER_REGISTRY="quay.io/influxdb/"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-$DEFAULT_DOCKER_REGISTRY}"

DEFAULT_INFLUXDB_VERSION="1.7"
INFLUXDB_VERSION="${INFLUXDB_VERSION:-$DEFAULT_INFLUXDB_VERSION}"
INFLUXDB_IMAGE=influxdb:${INFLUXDB_VERSION}-alpine

DEFAULT_INFLUXDB_V2_VERSION="nightly"
INFLUXDB_V2_VERSION="${INFLUXDB_V2_VERSION:-$DEFAULT_INFLUXDB_V2_VERSION}"
INFLUXDB_V2_IMAGE=${DOCKER_REGISTRY}influx:${INFLUXDB_V2_VERSION}

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

docker kill influxdb || true
docker rm influxdb || true
docker kill influxdb_v2 || true
docker rm influxdb_v2 || true
docker network rm influx_network || true
docker network create -d bridge influx_network --subnet 192.168.0.0/24 --gateway 192.168.0.1

echo
echo "Restarting InfluxDB [${INFLUXDB_IMAGE}] ..."
echo

#
# InfluxDB
#

docker pull --quiet "${INFLUXDB_IMAGE}" || true
docker run \
       --detach \
       --name influxdb \
       --network influx_network \
       --publish 8086:8086 \
       --publish 8089:8089/udp \
       --volume "${SCRIPT_PATH}"/influxdb.conf:/etc/influxdb/influxdb.conf \
       "${INFLUXDB_IMAGE}"

echo
echo "Wait to start InfluxDB..."
wget --quiet --spider --tries=20 --retry-connrefused --waitretry=5 http://localhost:8086/ping

docker exec -ti influxdb sh -c "influx -execute 'create database iot_writes'"

#
# InfluxDB 2.0
#
echo
echo "Restarting InfluxDB 2.0 [${INFLUXDB_V2_IMAGE}] ... "
echo

docker pull "${INFLUXDB_V2_IMAGE}" || true
docker run \
       --detach \
       --name influxdb_v2 \
       --network influx_network \
       --publish 9999:9999 \
       "${INFLUXDB_V2_IMAGE}"

echo 
echo "Wait to start InfluxDB 2.0"
wget --quiet --spider --tries=20 --retry-connrefused --waitretry=5 http://localhost:9999/metrics

docker exec -ti influxdb bash -c "apk add procps"
docker exec -ti influxdb_v2 bash -c "apt update -y && apt install -y procps"

echo
echo "Post onBoarding request, to setup initial user (my-user@my-password), org (my-org) and bucketSetup (my-bucket)"
echo
curl -s -X POST http://localhost:9999/api/v2/setup -H 'accept: application/json' \
    -d '{
            "username": "my-user",
            "password": "my-password",
            "org": "my-org",
            "bucket": "my-bucket",
            "token": "my-token"
        }'
