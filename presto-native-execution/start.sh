#!/bin/bash
# update_presto_config.sh

# 使用方法：
# ./update_presto_config.sh                # 自动从日志提取端口
# ./update_presto_config.sh 8080          # 手动指定端口
# ./update_presto_config.sh --port 8080   # 使用长参数
set -x
set -e

CONFIG_FILE="/home/candle/Project/Serverless/presto/presto-native-execution/etc/config.properties"
LOG_FILE="../output.log"

# 解析命令行参数
parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -p|--port)
                if [[ -n "$2" && "$2" =~ ^[0-9]+$ ]]; then
                    PORT="$2"
                    shift 2
                else
                    echo "错误: --port 需要指定端口号"
                    exit 1
                fi
                ;;
            -h|--help)
                echo "使用方法: $0 [选项]"
                echo "选项:"
                echo "  -p, --port PORT   手动指定端口号"
                echo "  -h, --help        显示帮助信息"
                echo "  -l, --log FILE    指定日志文件路径（默认: ../output.log）"
                exit 0
                ;;
            -l|--log)
                if [[ -n "$2" ]]; then
                    LOG_FILE="$2"
                    shift 2
                else
                    echo "错误: --log 需要指定日志文件路径"
                    exit 1
                fi
                ;;
            *)
                # 如果没有选项前缀，尝试作为端口号
                if [[ "$1" =~ ^[0-9]+$ ]]; then
                    PORT="$1"
                    shift
                else
                    echo "错误: 未知参数 '$1'"
                    echo "使用 $0 --help 查看帮助"
                    exit 1
                fi
                ;;
        esac
    done
}

# 自动提取端口
auto_extract_port() {
    if [[ ! -f "$LOG_FILE" ]]; then
        echo "错误: 日志文件不存在: $LOG_FILE"
        exit 1
    fi

    local extracted_port=$(grep -oP 'Discovery URL http://[^:]+:\K[0-9]+' "$LOG_FILE" | tail -1)

    if [[ -z "$extracted_port" ]]; then
        echo "错误: 无法从日志中提取端口号"
        echo "请确保日志文件包含 'Discovery URL' 行"
        exit 1
    fi

    echo "$extracted_port"
}

# 更新配置文件
update_config() {
    local port="$1"

    echo "使用的端口: $port"


    # 使用 sed 替换 discovery.uri 行的端口
    sed -i "s|discovery.uri=http://127.0.0.1:[0-9]\\+|discovery.uri=http://127.0.0.1:$port|" "$CONFIG_FILE"

    echo "配置已更新"

    # 显示更新后的配置行
    if [[ -f "$CONFIG_FILE" ]]; then
        echo "=== 更新后的配置 ==="
        grep -n "discovery.uri" "$CONFIG_FILE"
    fi
}

# 启动 Presto 服务器
start_presto_server() {
    echo -e "\n=== 启动 Presto 服务器 ==="
    ./_build/release/presto_cpp/main/presto_server \
        --logtostderr=1 \
        --v=1 \
        --etc_dir=/home/candle/Project/Serverless/presto/presto-native-execution/etc
}

# 主函数
main() {
    # 解析参数
    parse_arguments "$@"

    # 如果未指定端口，自动提取
    if [[ -z "$PORT" ]]; then
        echo "正在自动提取端口..."
        PORT=$(auto_extract_port)
        echo "从日志提取的端口: $PORT"
    else
        echo "使用手动指定的端口: $PORT"
    fi

    # 更新配置文件
    update_config "$PORT"

    # 启动服务器
    start_presto_server
}

# 运行主函数
main "$@"
