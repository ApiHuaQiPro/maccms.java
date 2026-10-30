"""B06（MACC-30）中规模合成库测量：Provide、ajax/data、ajax/suggest、站点级 RSS/搜索引擎地图经真实 HTTP 的耗时，以及实际执行的
SQL（取自应用的 MyBatis 调试日志，含绑定值）与 EXPLAIN。不进 ./mvnw verify；结果写入 --out 目录。

流程：新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql 与 openapi/provide/seed.sql（分类与少量行）→ 合成 300000 个视频
（只依赖序号的确定性数据：分类、状态、时间、年份、播放组；ALTER TABLE … DISABLE/ENABLE KEYS 与 ANALYZE 只在这个一次性库上）→
用 PHP CLI 生成临时旧配置（Provide 的 vod、art 打开，不缓存）→ 启动 target 中已打包的应用（LegacyRowMapper 打开 DEBUG
日志）→ 每个请求冷态 1 次 + 热态 3 次 → 从日志取实际 SQL 与绑定值做 EXPLAIN → 结束应用（按 PID）→ 删除一次性库并确认不存在。

管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP（默认 php）。
不连接 maccms_dev 或任何已有库。用法（仓库根目录，先 ./mvnw -o -q package -DskipTests）：
  python src/test/perf/b06_perf_run.py --out docs/architecture/16-b06-perf-raw
"""
import argparse
import http.client
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
BACKEND = REPO
LEGACY = REPO / 'php'  # 旧站 PHP（只读参照）
ROWS = 300000
DIGITS = '(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 ' \
         'UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)'

REQUESTS = [
    ('provide-list', '/api.php/provide/vod/?ac=list'),
    ('provide-list-xml', '/api.php/provide/vod/at/xml/?ac=list'),
    ('provide-list-pg100', '/api.php/provide/vod/?ac=list&pg=100'),
    ('provide-list-pg10000', '/api.php/provide/vod/?ac=list&pg=10000'),
    ('provide-videolist', '/api.php/provide/vod/?ac=videolist'),
    ('provide-detail-ids20', '/api.php/provide/vod/?ac=detail&ids=' + ','.join(str(1000 + i * 997) for i in range(20))),
    ('provide-t', '/api.php/provide/vod/?ac=list&t=3'),
    ('provide-h24', '/api.php/provide/vod/?ac=list&h=24'),
    ('provide-year', '/api.php/provide/vod/?ac=list&year=2015'),
    ('provide-year-range', '/api.php/provide/vod/?ac=list&year=2000-2020'),
    ('provide-isend', '/api.php/provide/vod/?ac=list&isend=1'),
    ('provide-wd', '/api.php/provide/vod/?ac=list&wd=' + urllib.parse.quote('合成视频12345')),
    ('provide-from', '/api.php/provide/vod/?ac=list&from=m3u8'),
    ('provide-from-videolist', '/api.php/provide/vod/?ac=videolist&from=m3u8'),
    ('ajax-data', '/index.php/ajax/data?mid=1&limit=30&page=20'),
    ('ajax-data-tid', '/index.php/ajax/data?mid=1&tid=1'),
    ('ajax-suggest', '/index.php/ajax/suggest?mid=1&wd=' + urllib.parse.quote('视频9999')),
    ('rss-index', '/index.php/rss/index.xml'),
    ('rss-baidu', '/index.php/rss/baidu.xml'),
    ('map', '/index.php/map/index.html'),
]


def env(name, default=None):
    v = os.environ.get(name, default)
    if not v:
        sys.exit('缺少环境变量 ' + name)
    return v


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Db:
    def __init__(self):
        m = re.match(r'jdbc:mysql://([^:/?]+)(?::(\d+))?', env('MACCMS_IT_ADMIN_URL'))
        self.host, self.port = m.group(1), m.group(2) or '3306'
        self.user, self.password = env('MACCMS_IT_ADMIN_USERNAME'), env('MACCMS_IT_ADMIN_PASSWORD')
        self.name = 'maccms_it_' + datetime.now().strftime('%Y%m%d%H%M%S') + '_' + secrets.token_hex(4)
        assert re.fullmatch(r'maccms_it_[0-9]{14}_[0-9a-f]{8}', self.name)

    def run(self, sql, db=None, extra=()):
        args = ['mysql', '-h', self.host, '-P', self.port, '-u', self.user, '--default-character-set=utf8mb4', '-N',
                *extra]
        if db:
            args.append(db)
        r = subprocess.run(args, input=sql.encode('utf-8'), capture_output=True,
                           env=dict(os.environ, MYSQL_PWD=self.password))
        if r.returncode != 0:
            raise RuntimeError('mysql 失败: ' + r.stderr.decode('utf-8', 'replace')[:300])
        return r.stdout.decode('utf-8')

    def create(self, log):
        self.run('CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8' % self.name)
        assert self.run("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%s'" % self.name).strip() == '0'
        self.run((LEGACY / 'application/install/sql/install.sql').read_text(encoding='utf-8'), self.name)
        self.run((BACKEND / 'src/test/resources/openapi/provide/seed.sql').read_text(encoding='utf-8'), self.name)
        t = time.time()
        nums = ('SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 AS n FROM ' +
                ', '.join('%s %s' % (DIGITS, x) for x in 'abcdef'))
        sql = ("ALTER TABLE mac_vod DISABLE KEYS;\n"
               "INSERT INTO mac_vod (vod_id,type_id,type_id_1,vod_name,vod_en,vod_status,vod_letter,vod_pic,"
               "vod_pic_screenshot,vod_remarks,vod_area,vod_lang,vod_year,vod_isend,vod_time,vod_time_add,vod_content,"
               "vod_play_from,vod_play_server,vod_play_note,vod_play_url,vod_down_url,vod_plot_name,vod_plot_detail) "
               "SELECT n + 1000, ELT(n % 4 + 1, 2, 3, 4, 11), IF(n % 4 < 2, 1, 0), CONCAT('合成视频', n), CONCAT('v', n),"
               " IF(n % 50 = 0, 0, 1), CHAR(65 + n % 26), CONCAT('upload/vod/', n, '.jpg'), '', CONCAT('第', n % 40, '集'),"
               " ELT(n % 5 + 1, '大陆', '美国', '日本', '韩国', '香港'), '国语', 2000 + n % 22, n % 2,"
               " 1600000000 + (n * 7919) % 90000000, 1590000000 + n, REPEAT('合成正文。', 60),"
               " ELT(n % 5 + 1, 'aaa', 'bbb', 'aaa$$$bbb', 'ccc', 'm3u8'), 'no', '',"
               " CONCAT('第1集$http://media.example.test/', n, '/1.m3u8#第2集$http://media.example.test/', n, '/2.m3u8'),"
               " '', '', '' FROM (" + nums + ") t WHERE n < " + str(ROWS) + ";\n"
               "ALTER TABLE mac_vod ENABLE KEYS;\nANALYZE TABLE mac_vod;\n")
        self.run(sql, self.name)
        log['generateSeconds'] = round(time.time() - t, 1)
        log['context'] = self.run("SELECT COUNT(*), SUM(vod_status=1), ROUND(AVG(LENGTH(vod_content))),"
                                  " ROUND(AVG(LENGTH(vod_play_url))) FROM mac_vod", self.name).strip()

    def drop(self):
        self.run('DROP DATABASE `%s`' % self.name)
        return self.run("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='%s'" % self.name).strip() == '0'


def legacy_config(work):
    target = work / 'legacy-config'
    (target / 'extra').mkdir(parents=True)
    for f in (LEGACY / 'application/extra').glob('*.php'):
        shutil.copy(f, target / 'extra' / f.name)
    shutil.copy(LEGACY / 'application/route.php', target / 'route.php')
    script = ("$c = include $argv[1]; $c['api']['vod']['status'] = '1'; $c['api']['art']['status'] = '1';"
              "file_put_contents($argv[2], '<?php' . PHP_EOL . 'return ' . var_export($c, true) . ';' . PHP_EOL);")
    subprocess.run([env('PHP', 'php'), '-r', script, str(LEGACY / 'application/extra/maccms.php'),
                    str(target / 'extra/maccms.php')], check=True)
    return target


def start_app(db, legacy, log_path):
    port = free_port()
    jar = next((BACKEND / 'target').glob('maccms-backend-*.jar'))
    args = ['java', '-jar', str(jar),
            '--spring.datasource.url=jdbc:mysql://%s:%s/%s?characterEncoding=UTF-8&useSSL=false' % (db.host, db.port, db.name),
            '--spring.datasource.username=' + db.user, '--server.port=%d' % port, '--server.address=127.0.0.1',
            '--maccms.site.enabled=true', '--maccms.openapi.enabled=true', '--maccms.site.base-url=http://maccms.local.test/',
            '--maccms.site.search.enabled=true', '--maccms.content.search.enabled=true',
            '--maccms.legacy-config.dir=' + str(legacy),
            '--logging.level.com.maccms.backend.content.infrastructure.LegacyRowMapper=DEBUG']
    if db.user.lower() == 'root':
        args.append('--maccms.datasource.allow-root=true')
    log = open(log_path, 'wb')
    proc = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                            env=dict(os.environ, SPRING_DATASOURCE_PASSWORD=db.password, MACCMS_SITE_NAME='合成站'))
    deadline = time.time() + 120
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError('应用启动失败')
        try:
            c = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
            c.request('GET', '/api.php/provide/actor/')
            if c.getresponse().status == 200:
                return proc, log, port
        except OSError:
            pass
        time.sleep(0.5)
    raise RuntimeError('应用启动超时')


def timed(port, path):
    t = time.perf_counter()
    c = http.client.HTTPConnection('127.0.0.1', port, timeout=60)
    c.request('GET', path)
    r = c.getresponse()
    body = r.read()
    return round((time.perf_counter() - t) * 1000, 1), r.status, len(body)


def statements(log_text):
    """MyBatis 调试日志中的 Preparing/Parameters 对 → 带绑定值的 SQL。"""
    out = []
    lines = log_text.splitlines()
    for i, line in enumerate(lines):
        m = re.search(r'==>\s+Preparing: (.*)$', line)
        if not m:
            continue
        sql = m.group(1)
        params = []
        for j in range(i + 1, min(i + 4, len(lines))):
            p = re.search(r'==> Parameters: (.*)$', lines[j])
            if p:
                params = [x for x in re.findall(r'(.*?)\((String|Integer|Long|Boolean)\)(?:, |$)', p.group(1))]
                break
        bound = sql
        for value, kind in params:
            v = value if kind in ('Integer', 'Long') else "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
            bound = bound.replace('?', v, 1)
        out.append(bound)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    out = (REPO / a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='maccms-b06-perf-'))
    db = Db()
    run = {'started': datetime.now().isoformat(timespec='seconds'), 'database': db.name, 'rows': ROWS}
    proc = None
    try:
        db.create(run)
        legacy = legacy_config(work)
        proc, log, port = start_app(db, legacy, work / 'app.log')
        run['pid'] = proc.pid
        bench = []
        sql_by_request = {}
        for name, path in REQUESTS:
            before = (work / 'app.log').read_text(encoding='utf-8', errors='replace')
            cold = timed(port, path)
            after = (work / 'app.log').read_text(encoding='utf-8', errors='replace')
            sql_by_request[name] = statements(after[len(before):])
            warm = [timed(port, path) for _ in range(3)]
            ms = sorted(w[0] for w in warm)
            bench.append({'name': name, 'path': path, 'status': cold[1], 'bytes': cold[2], 'coldMs': cold[0],
                          'warmMs': ms, 'warmMedianMs': ms[1]})
            print(name, cold, ms, flush=True)
        explains = []
        for name, sqls in sql_by_request.items():
            for sql in sqls:
                plan = db.run('EXPLAIN ' + sql, db.name, extra=('--table',))
                plan = '\n'.join(line.rstrip() for line in plan.strip().splitlines())
                explains.append('## %s\n%s\n%s\n' % (name, sql, plan))
        (out / 'bench.json').write_text(json.dumps(bench, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
        (out / 'explain.txt').write_text('\n'.join(explains), encoding='utf-8')
    finally:
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(30)
            except subprocess.TimeoutExpired:
                proc.kill()
            log.close()
        run['databaseDropped'] = db.drop()
        run['finished'] = datetime.now().isoformat(timespec='seconds')
        (out / 'run.json').write_text(json.dumps(run, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
        shutil.rmtree(work, ignore_errors=True)
    print('dropped', run['databaseDropped'])


if __name__ == '__main__':
    main()
