# 使用Ubuntu LTS版本
FROM  ubuntu:22.04

# 设置非交互式安装，避免提示
ENV DEBIAN_FRONTEND=noninteractive

# 安装OpenJDK 17和必要依赖
RUN apt-get update && \
    apt-get install -y \
    openjdk-17-jdk \
    maven \
    python3 \
    python3-pip \
    g++ \
    cmake \
    ninja-build \
    git \
    wget \
    curl \
    tar \
    gzip \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# 设置Java环境变量
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# 验证安装
RUN java -version && mvn --version

# 设置工作目录
WORKDIR /app

# 复制编译结果
COPY target/ /app/target/

# 复制启动脚本
COPY run_test.sh /app/run_test.sh
RUN chmod +x /app/run_test.sh

ENTRYPOINT ["/app/run_test.sh"]
