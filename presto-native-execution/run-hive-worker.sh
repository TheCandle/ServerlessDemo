#!/bin/bash
cd ~/Project/Serverless/presto/presto-native-execution/target

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

# 添加项目其他模块的依赖（关键！）
# 找到所有 Presto 模块的 jar 包
PRESTO_MODULES=""
# for module in ~/Project/Serverless/presto/presto-*/target/presto-*.jar; do
#     if [[ -f "$module" ]]; then
#         PRESTO_MODULES="$PRESTO_MODULES:$module"
#     fi
# done

CLASSPATH="$CLASSPATH$PRESTO_MODULES"

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
  -DPRESTO_SERVER=/home/candle/Project/Serverless/presto/presto-native-execution/_build/release/presto_cpp/main/presto_server \
  -DDATA_DIR=/tmp/data \
  -DWORKER_COUNT=0 \
  -cp "$CLASSPATH" \
  com.facebook.presto.nativeworker.HiveExternalWorkerQueryRunner \
  --catalog hive \
  --schema default
