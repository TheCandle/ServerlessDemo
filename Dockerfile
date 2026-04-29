FROM eclipse-temurin:17-jdk-jammy
# 安装 Python 3 并创建 python 软链接（presto launcher 默认调用 python）
# RUN apt-get update && apt-get install -y python3 \
#     && ln -s /usr/bin/python3 /usr/bin/python \
#     && rm -rf /var/lib/apt/lists/*


# 换源
RUN sed -i 's/archive.ubuntu.com/mirrors.aliyun.com/g' /etc/apt/sources.list \
    && sed -i 's/security.ubuntu.com/mirrors.aliyun.com/g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y python3 \
    && ln -s /usr/bin/python3 /usr/bin/python \
    && rm -rf /var/lib/apt/lists/*


# 可选：如果需要从源码包构建，可以在这里解压，但你已经编译好了，直接复制
# 我们直接复制编译好的 presto-server 目录
# ----------------------------------------------
# 设置工作目录
WORKDIR /opt/presto

# 将本地编译好的 presto-server 目录复制到镜像中
# 注意：这里的路径要替换为你实际的编译输出路径
COPY presto-server/target/presto-server-0.297-SNAPSHOT /opt/presto

# 如果希望使用你自己的配置文件，可以复制本地 etc 目录到镜像中
# 如果不想内置配置文件，可以跳过，运行时通过卷挂载
COPY presto-server/target/presto-server-0.297-SNAPSHOT/etc/ /opt/presto/etc

# 创建 presto 用户（非 root 运行更安全）
RUN groupadd --system --gid 1000 presto \
    && useradd --system --gid presto --uid 1000 --shell /bin/bash presto \
    && mkdir -p /var/presto/data /var/presto/log \
    && chown -R presto:presto /opt/presto /var/presto

# 切换到 presto 用户
USER presto

# 设置 Presto 环境变量
ENV PRESTO_HOME=/opt/presto
ENV PATH=$PRESTO_HOME/bin:$PATH

# 声明 Presto 默认 HTTP 端口（根据你的 config.properties 设置）
EXPOSE 8080

# 可选：声明数据卷，方便外部挂载配置和数据
VOLUME ["/opt/presto/etc", "/var/presto/data", "/var/presto/log"]

# 设置容器启动命令：直接在前台运行 Presto
ENTRYPOINT ["bin/launcher"]
CMD ["run"]
