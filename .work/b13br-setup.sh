#!/usr/bin/env bash
# B13 browser check environment (local only): disposable DB maccms_it_*, temp legacy config, temp upload/, jar, throwaway nginx.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$ROOT/.work/b13br"
M="mysql -h127.0.0.1 -uroot -p${MYSQL_LOCAL_PW} --default-character-set=utf8"
if [ "${RESET:-0}" = 1 ]; then
  # 复位：同一个一次性库删掉重建、upload/ 清空，jar 与 nginx 不动
  DB=$(cat "$W/db.txt"); [[ "$DB" =~ ^maccms_it_[0-9]{14}_[0-9a-f]{8}$ ]] || { echo "bad db name"; exit 2; }
  $M -e "DROP DATABASE \`$DB\`"; rm -rf "$W/site" "$W/shots" "$W/legacy"; mkdir -p "$W/legacy/extra" "$W/site/upload" "$W/shots"
else
  rm -rf "$W"; mkdir -p "$W/legacy/extra" "$W/site/upload" "$W/nginx/conf" "$W/nginx/logs" "$W/nginx/temp" "$W/shots"
  DB="maccms_it_$(date +%Y%m%d%H%M%S)_$(python -c 'import secrets;print(secrets.token_hex(4))')"
  [[ "$DB" =~ ^maccms_it_[0-9]{14}_[0-9a-f]{8}$ ]] || { echo "bad db name"; exit 2; }
  echo "$DB" > "$W/db.txt"
fi
$M -e "CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8"
$M "$DB" < "$ROOT/application/install/sql/install.sql"
$M "$DB" < "$ROOT/application/install/sql/initdata.sql"
NOW=$(date +%s)
$M "$DB" <<SQL
INSERT INTO mac_admin (admin_name,admin_pwd,admin_status,admin_auth) VALUES ('admin',MD5('b13-browser-pass'),1,'');
INSERT INTO mac_link (link_type,link_name,link_sort,link_add_time,link_time,link_url,link_logo) VALUES
 (0,'文字友链',1,$NOW,$NOW,'http://a.example.test/',''),(1,'图片友链',2,$NOW,$NOW,'http://b.example.test/','upload/link/logo.png');
INSERT INTO mac_comment (comment_mid,comment_rid,comment_pid,user_id,comment_status,comment_name,comment_ip,comment_time,comment_content)
 VALUES (1,1,0,0,0,'访客甲',2130706433,$NOW,'待审核的评论'),(1,1,0,0,1,'访客乙',2130706433,$NOW,'已审核的评论');
INSERT INTO mac_gbook (gbook_rid,user_id,gbook_status,gbook_name,gbook_ip,gbook_time,gbook_reply_time,gbook_content,gbook_reply)
 VALUES (0,0,0,'访客丙',2130706433,$NOW,0,'留言内容','');
INSERT INTO mac_annex (annex_time,annex_file,annex_size,annex_type) VALUES ($NOW,'upload/link/logo.png',1,'image'),
 ($NOW,'upload/vod/20260101-1/gone.jpg',3,'image');
SQL
# legacy config: repository application/extra/* with admin login captcha off, local upload, thumbnail + watermark on
cp "$ROOT"/application/extra/*.php "$W/legacy/extra/"
D:/Tools/php/8.0/php.exe -r '
$c = include $argv[1];
$c["app"]["admin_login_verify"] = "0";
$c["upload"]["mode"] = "local"; $c["upload"]["protocol"] = "http";
$c["upload"]["thumb"] = "1"; $c["upload"]["thumb_size"] = "120x80"; $c["upload"]["thumb_type"] = "1";
$c["upload"]["watermark"] = "1"; $c["upload"]["watermark_location"] = "9"; $c["upload"]["watermark_content"] = "B13";
$c["upload"]["watermark_size"] = "20"; $c["upload"]["watermark_color"] = "#FF0000"; $c["upload"]["watermark_font"] = "";
file_put_contents($argv[2], "<?php\nreturn " . var_export($c, true) . ";\n");
' "$ROOT/application/extra/maccms.php" "$W/legacy/extra/maccms.php"
mkdir -p "$W/site/upload/link" "$W/site/upload/vod/20260101-1"
python - "$W/site/upload" <<'PY'
import sys, zlib, struct
def png(w, h, rgb):
    raw = b''.join(b'\x00' + bytes(rgb) * w for _ in range(h))
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')
open(sys.argv[1] + '/link/logo.png', 'wb').write(png(88, 31, (30, 120, 200)))
open(sys.argv[1] + '/../upload-sample.png', 'wb').write(png(640, 360, (200, 80, 40)))
PY
[ "${RESET:-0}" = 1 ] && { echo "reset DB=$DB"; exit 0; }
# throwaway nginx (own prefix and config; the shared D:/Tools/nginx config is not touched)
cp D:/Tools/nginx/conf/mime.types "$W/nginx/conf/"
cat > "$W/nginx/conf/nginx.conf" <<NGX
worker_processes 1;
events { worker_connections 64; }
http {
  include mime.types;
  client_max_body_size 4m;
  server {
    listen 127.0.0.1:18297;
    location /upload/ { root "$(cygpath -m "$W/site")"; }
    location / { proxy_pass http://127.0.0.1:18296; proxy_set_header Host \$http_host; }
  }
}
NGX
echo "DB=$DB W=$W"
