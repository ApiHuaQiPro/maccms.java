#!/usr/bin/env bash
# MACC-19 一次性库测量的完整顺序（Git Bash，在 backend/ 下执行；先 ./mvnw -q package -DskipTests）。
# 需要 MACCMS_IT_ADMIN_URL / MACCMS_IT_ADMIN_USERNAME / MACCMS_IT_ADMIN_PASSWORD；不读取、不写入任何其他配置。
# 无论成功失败，退出时都删除本次创建的一次性库（state.properties 存在时）。原始结果在 target/perf/。
set -euo pipefail
CP="${MYSQL_CONNECTOR_JAR:-$HOME/.m2/repository/com/mysql/mysql-connector-j/8.2.0/mysql-connector-j-8.2.0.jar}"
P=(java -cp "$CP" src/test/perf/SearchPerf.java)
LOCK_SECONDS="${LOCK_SECONDS:-30}"

cleanup() {
  if [ -f target/perf/state.properties ]; then
    "${P[@]}" drop
  fi
}
trap cleanup EXIT

"${P[@]}" create
for scale in 100000:s100k 500000:s500k 1000000:s1m; do
  rows="${scale%%:*}"
  label="${scale##*:}"
  "${P[@]}" grow "$rows"
  "${P[@]}" context "$label"
  "${P[@]}" bench "$label"
  "${P[@]}" sqlcheck "$label"
  "${P[@]}" lock "$label" "$LOCK_SECONDS"
done
"${P[@]}" timeout s1m
