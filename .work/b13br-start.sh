#!/usr/bin/env bash
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; W="$ROOT/.work/b13br"; DB=$(cat $W/db.txt); WM=$(cygpath -m "$W")
URL="jdbc:mysql://127.0.0.1:3306/$DB?characterEncoding=UTF-8&useSSL=false"
export MSYS2_ENV_CONV_EXCL='*' MSYS_NO_PATHCONV=1
MACCMS_DB_URL="$URL" MACCMS_DB_USERNAME=root MACCMS_DB_PASSWORD="$MYSQL_LOCAL_PW" MACCMS_DB_ALLOW_ROOT=true \
MACCMS_WRITE_ENABLED=true MACCMS_WRITE_DB_URL="$URL" MACCMS_WRITE_DB_USERNAME=root MACCMS_WRITE_DB_PASSWORD="$MYSQL_LOCAL_PW" MACCMS_WRITE_DB_ALLOW_ROOT=true \
MACCMS_ADMIN_ENABLED=true MACCMS_ADMIN_ENTRY=/_admin.php MACCMS_ADMIN_BASE_URL=http://127.0.0.1:18297/ \
MACCMS_LEGACY_CONFIG_DIR="$WM/legacy" MACCMS_UPLOAD_DIR="$WM/site/upload" MACCMS_MEMBER_UPLOAD_DIR="$WM/site/upload" SERVER_PORT=18296 \
nohup java -jar "$(cygpath -m "$ROOT")/backend/target/maccms-backend-0.1.0-SNAPSHOT.jar" > $W/jar.log 2>&1 < /dev/null &
disown
