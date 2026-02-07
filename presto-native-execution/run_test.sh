#!/bin/bash

cd /app/target

# 构建完整的类路径
CLASSPATH="."

# 添加主jar包
CLASSPATH="$CLASSPATH:presto-native-execution-0.297-SNAPSHOT.jar"
CLASSPATH="$CLASSPATH:presto-native-execution-0.297-SNAPSHOT-tests.jar"

# 添加所有依赖
CLASSPATH="$CLASSPATH:classes"
CLASSPATH="$CLASSPATH:test-classes"
CLASSPATH="$CLASSPATH:dependency/*"
CLASSPATH="$CLASSPATH:test-dependency/*"
CLASSPATH="$CLASSPATH:compile-dependency/*"

echo "Classpath length: ${#CLASSPATH}"
echo "Starting HiveExternalWorkerQueryRunner..."

# 运行 HiveExternalWorkerQueryRunner
java \
  --add-opens java.base/java.net=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/sun.net.util=ALL-UNNAMED \
  --add-exports java.base/sun.net.util=ALL-UNNAMED \
  -ea \
  -Xmx5G \
  -XX:+ExitOnOutOfMemoryError \
  -Duser.timezone=America/Bahia_Banderas \
  -Dhive.security=legacy \
  -DDATA_DIR=/tmp/data \
  -DWORKER_COUNT=0 \
  -cp "$CLASSPATH" \
  com.facebook.presto.nativeworker.HiveExternalWorkerQueryRunner \
  --catalog hive \
  --schema default "$@" &
echo "start watchdog"
/usr/bin/fwatchdog
#  -DPRESTO_SERVER=/app/presto_server \
