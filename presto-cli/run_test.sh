#!/bin/bash

cd /app/target

# 构建完整的类路径
CLASSPATH="."

# 添加主jar包
CLASSPATH="$CLASSPATH:presto-cli-0.297-SNAPSHOT-executable.jar"

# 添加所有依赖
CLASSPATH="$CLASSPATH:classes"
CLASSPATH="$CLASSPATH:test-classes"
CLASSPATH="$CLASSPATH:dependency/*"
CLASSPATH="$CLASSPATH:test-dependency/*"
CLASSPATH="$CLASSPATH:compile-dependency/*"

java -cp "$CLASSPATH" -jar /app/target/presto-cli-*-executable.jar \
--server 127.0.0.1:8081 \
--catalog hive \
--schema tpch

tail -f /dev/null
