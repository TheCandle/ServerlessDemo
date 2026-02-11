#!/bin/sh
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

echo "SERVER_HOST=$SERVER_HOST"
echo "SERVER_PORT=$SERVER_PORT"
echo "WORKER_PORT=$WORKER_PORT"

sed -i "s|http://<replace_host>:<replace_port>|http://$SERVER_HOST:$SERVER_PORT|" /opt/presto-server/etc/config.properties
sed -i "s|http-server.http.port=7777|http-server.http.port=$WORKER_PORT|" /opt/presto-server/etc/config.properties

GLOG_logtostderr=1 presto_server --etc-dir=/opt/presto-server/etc &
echo "start watchdog"
/usr/bin/fwatchdog
