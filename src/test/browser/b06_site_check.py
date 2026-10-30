"""B06（MACC-30）最终树的真实 HTTP 与浏览器检查。不进 ./mvnw verify；结果写入 --out 目录（summary.json、截图）。

流程：新建一次性库 maccms_it_<时间>_<随机>（导入 install.sql、openapi/provide/seed.sql 与 phase-diff.sql，全部合成数据）→ 用 PHP CLI
把仓库 application/extra/maccms.php 复制到临时旧配置目录并打开 Provide 的 vod/art（不改仓库文件）→ 依次以 8 组部署参数启动
target 中已打包的应用（W1/W2 = 00/01/10/11 × 根路径 / 子目录 /sub/；子目录由本脚本内置的本地反向代理把 /sub/ 映射到应用根
路径，与 05 文档第 2 节相同）→ 对每组：
  1. 真实 HTTP：地图、帮助、版权页、站点级 RSS 与 6 种搜索引擎地图、自定义标签页、二维码、桌面快捷方式、联想词、ajax/data 与 Provide
     的状态码、MIME、编码、缓存头、nosniff、页面 CSP、canonical 与站内链接前缀、XML/JSON 可解析、外部主机只按 W2 出现在页面数据中；
  2. Chromium（Playwright，无头）：所有非本机请求在浏览器内被拦截并记录（不访问任何外部站点）；页面无 CSP 违规；
  3. 根路径 W1/W2=11 时另做：320/360/768/1280 宽度无横向溢出（截图，另做长文本压力）、键盘 Tab 顺序与可见焦点、prefers-reduced-motion（reduce 下三页无动画与过渡）。
→ 结束每个应用子进程（只按本脚本启动的 PID）→ 删除一次性库并确认已不存在。

管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL（jdbc:mysql://127.0.0.1:3306/）、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；
另需 PHP（环境变量 PHP，默认 php）。不连接 maccms_dev 或任何已有库，不改 nginx，不读仓库外配置。用法（仓库根目录）：
  python src/test/browser/b06_site_check.py --out docs/architecture/16-b06-site-check
"""
import argparse
import http.client
import http.server
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import xml.dom.minidom
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
BACKEND = REPO
LEGACY = REPO / 'php'  # 旧站 PHP（只读参照）
# 合成数据中的外部图片主机（seed.sql：mac://img.example.test/…、//img.example.test/…、http://img.example.test/…）
IMAGE_HOST = 'img.example.test'
MEDIA_HOST = 'media.example.test'
EXTERNAL = re.compile(r'[a-z0-9-]+\.example\.test')


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
        url = env('MACCMS_IT_ADMIN_URL')
        m = re.match(r'jdbc:mysql://([^:/?]+)(?::(\d+))?', url)
        self.host, self.port = m.group(1), m.group(2) or '3306'
        self.user, self.password = env('MACCMS_IT_ADMIN_USERNAME'), env('MACCMS_IT_ADMIN_PASSWORD')
        self.name = 'maccms_it_' + datetime.now().strftime('%Y%m%d%H%M%S') + '_' + secrets.token_hex(4)
        assert re.fullmatch(r'maccms_it_[0-9]{14}_[0-9a-f]{8}', self.name)

    def run(self, sql, db=None):
        args = ['mysql', '-h', self.host, '-P', self.port, '-u', self.user, '--default-character-set=utf8', '-N']
        if db:
            args.append(db)
        e = dict(os.environ, MYSQL_PWD=self.password)
        r = subprocess.run(args, input=sql.encode('utf-8'), capture_output=True, env=e)
        if r.returncode != 0:
            raise RuntimeError('mysql 失败: ' + r.stderr.decode('utf-8', 'replace')[:300])
        return r.stdout.decode('utf-8')

    def create(self):
        self.run('CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8' % self.name)
        self.run((LEGACY / 'application/install/sql/install.sql').read_text(encoding='utf-8'), self.name)
        for f in ('seed.sql', 'phase-diff.sql'):
            self.run((BACKEND / 'src/test/resources/openapi/provide' / f).read_text(encoding='utf-8'), self.name)

    def drop(self):
        self.run('DROP DATABASE `%s`' % self.name)
        left = self.run("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='%s'" % self.name).strip()
        return left == '0'


def legacy_config(work):
    """临时旧配置目录：仓库 extra/*.php 与 route.php 的副本，maccms.php 中打开 Provide 的 vod、art（status=1，不收费）。"""
    target = work / 'legacy-config'
    (target / 'extra').mkdir(parents=True)
    for f in (LEGACY / 'application/extra').glob('*.php'):
        shutil.copy(f, target / 'extra' / f.name)
    shutil.copy(LEGACY / 'application/route.php', target / 'route.php')
    script = ("$c = include $argv[1]; $c['api']['vod']['status'] = '1'; $c['api']['art']['status'] = '1';"
              "$c['api']['vod']['cachetime'] = '30';"
              "file_put_contents($argv[2], '<?php' . PHP_EOL . 'return ' . var_export($c, true) . ';' . PHP_EOL);")
    subprocess.run([env('PHP', 'php'), '-r', script, str(LEGACY / 'application/extra/maccms.php'),
                    str(target / 'extra/maccms.php')], check=True)
    return target


class Proxy(threading.Thread):
    """把 /sub/ 映射到应用根路径的本地反向代理（子目录部署），只转发到 127.0.0.1 的应用端口。"""

    def __init__(self, port):
        super().__init__(daemon=True)
        self.port = port
        self.target = None
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def handle_one(self, method):
                if not self.path.startswith('/sub/'):
                    self.send_response(404)
                    self.end_headers()
                    return
                length = int(self.headers.get('Content-Length') or 0)
                body = self.rfile.read(length) if length else None
                c = http.client.HTTPConnection('127.0.0.1', outer.target, timeout=30)
                headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection')}
                headers['Host'] = '127.0.0.1:%d' % outer.port
                c.request(method, self.path[len('/sub'):], body=body, headers=headers)
                r = c.getresponse()
                data = r.read()
                self.send_response(r.status)
                for k, v in r.getheaders():
                    if k.lower() not in ('transfer-encoding', 'connection', 'content-length'):
                        self.send_header(k, v)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                if method != 'HEAD':
                    self.wfile.write(data)

            def do_GET(self):
                self.handle_one('GET')

            def do_HEAD(self):
                self.handle_one('HEAD')

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)

    def run(self):
        self.server.serve_forever()


class App:
    def __init__(self, db, w1, w2, base, legacy_dir, log_dir):
        self.port = free_port()
        jar = next((BACKEND / 'target').glob('maccms-backend-*.jar'))
        args = ['java', '-jar', str(jar),
                '--spring.datasource.url=jdbc:mysql://%s:%s/%s?characterEncoding=UTF-8&useSSL=false' % (
                    db.host, db.port, db.name),
                '--spring.datasource.username=' + db.user, '--server.port=%d' % self.port,
                '--server.address=127.0.0.1', '--maccms.site.enabled=true', '--maccms.openapi.enabled=true',
                '--maccms.site.base-url=' + base,
                '--maccms.site.search.enabled=true', '--maccms.content.search.enabled=true',
                '--maccms.legacy-config.dir=' + str(legacy_dir),
                '--maccms.browser-external.w1=' + ('true' if w1 else 'false'),
                '--maccms.browser-external.w2=' + ('true' if w2 else 'false')]
        if db.user.lower() == 'root':
            args.append('--maccms.datasource.allow-root=true')
        self.log = open(log_dir / ('app-%d%d-%s.log' % (w1, w2, 'sub' if '/sub/' in base else 'root')), 'wb')
        self.proc = subprocess.Popen(args, stdout=self.log, stderr=subprocess.STDOUT,
                                     env=dict(os.environ, SPRING_DATASOURCE_PASSWORD=db.password,
                                              # 非 ASCII 值经环境变量传入（Windows 命令行参数按 ANSI 代码页转换会丢字）
                                              MACCMS_SITE_NAME='合成站'))
        deadline = time.time() + 120
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError('应用启动失败，见 ' + self.log.name)
            try:
                c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=2)
                c.request('GET', '/index.php/map/index.html')
                if c.getresponse().status == 200:
                    return
            except OSError:
                pass
            time.sleep(0.5)
        raise RuntimeError('应用启动超时')

    def stop(self):
        # 只结束本脚本启动的这个子进程（按 PID）
        self.proc.terminate()
        try:
            self.proc.wait(30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.close()


def ct(headers):
    """Content-Type 规范化（Tomcat 输出 text/xml;charset=utf-8，不带空格）。"""
    return headers.get('content-type', '').lower().replace(' ', '')


def fetch(port, path, method='GET', headers=None):
    c = http.client.HTTPConnection('127.0.0.1', port, timeout=30)
    c.request(method, path, headers=headers or {})
    r = c.getresponse()
    return r.status, {k.lower(): v for k, v in r.getheaders()}, r.read()


HTML = {'map': 'index.php/map/index.html', 'help': 'index.php/help/index.html',
        'copyright': 'index.php/vod/copyright/id/1.html'}
FEEDS = ['index'] + ['baidu', 'google', 'so', 'sogou', 'bing', 'sm']


def http_checks(port, prefix, w1, w2, result):
    out = {}
    f = result['failures']

    def check(name, cond, info):
        out[name] = info
        if not cond:
            f.append('http %s %s' % (name, json.dumps(info, ensure_ascii=False)[:400]))

    for name, rel in HTML.items():
        status, h, raw = fetch(port, prefix + rel)
        body = raw.decode('utf-8')
        canonical = re.search(r'<link rel="canonical" href="([^"]+)"', body)
        links = re.findall(r'(?:href|src|action)="(/[^"]*)"', body)
        wrong = [l for l in links if not l.startswith(prefix)]
        hosts = sorted(set(EXTERNAL.findall(body)))
        info = {'status': status, 'type': h.get('content-type'), 'cache': h.get('cache-control'),
                'csp': h.get('content-security-policy', '')[:120], 'canonical': canonical.group(1) if canonical else None,
                'linksOutsidePrefix': wrong[:5], 'externalHosts': hosts}
        check(name, status == 200 and h.get('content-type', '').lower() == 'text/html;charset=utf-8'
              and "script-src 'none'" in h.get('content-security-policy', '') and h.get('x-content-type-options') == 'nosniff'
              and canonical and canonical.group(1).endswith(prefix + rel) and not wrong and not hosts and '合成站' in body,
              info)
    for feed in FEEDS:
        status, h, raw = fetch(port, prefix + 'index.php/rss/%s.xml' % feed)
        try:
            doc = xml.dom.minidom.parseString(raw)
            root = doc.documentElement.tagName
            locs = [n.firstChild.data for n in doc.getElementsByTagName('loc' if feed != 'index' else 'link')]
        except Exception as e:  # noqa: BLE001 记录为失败
            root, locs = 'parse error %s' % type(e).__name__, []
        expected = 'application/rss+xml;charset=utf-8' if feed == 'index' else 'application/xml;charset=utf-8'
        bad = [l for l in locs if not l.startswith('http://' if prefix == '/' else 'http://127.0.0.1')]
        info = {'status': status, 'type': h.get('content-type'), 'root': root, 'locs': len(locs), 'badLocs': bad[:3]}
        check('rss-' + feed, status == 200 and h.get('content-type', '').lower() == expected and not bad and locs
              and root == ('rss' if feed == 'index' else 'urlset'), info)
    status, h, raw = fetch(port, prefix + 'index.php/label/notice.html')
    check('label', status == 404 and '非法请求' in raw.decode('utf-8'), {'status': status})
    status, h, raw = fetch(port, prefix + 'index.php/qrcode/index.html?url=' + urllib.parse.quote(
        'http://maccms.local.test/index.php/vod/detail/id/1.html', safe=''))
    check('qrcode', status == 200 and h.get('content-type') == 'image/png' and raw[:4] == b'\x89PNG'
          and 'max-age=86400' in h.get('cache-control', ''), {'status': status, 'type': h.get('content-type'),
                                                               'bytes': len(raw), 'cache': h.get('cache-control')})
    status, h, raw = fetch(port, prefix + 'index.php/qrcode/index.html?url=' + urllib.parse.quote('javascript:x'))
    check('qrcode-invalid', status == 200 and raw == b'', {'status': status, 'bytes': len(raw)})
    status, h, raw = fetch(port, prefix + 'index.php/ajax/desktop')
    check('desktop', status == 200 and h.get('content-type') == 'application/octet-stream'
          and "filename*=UTF-8''" in h.get('content-disposition', '') and raw.startswith(b'[InternetShortcut]\r\n')
          and h.get('cache-control') == 'no-store', {'status': status, 'disposition': h.get('content-disposition'),
                                                     'firstLine': raw[:60].decode('utf-8', 'replace')})
    status, h, raw = fetch(port, prefix + 'index.php/ajax/desktop?name=a&url=x%0D%0AIconFile%3D%5C%5Chost%5Cshare')
    check('desktop-injection', status == 400, {'status': status})
    status, h, raw = fetch(port, prefix + 'index.php/ajax/data?mid=1')
    data = raw.decode('utf-8')
    try:
        parsed = json.loads(data)
    except ValueError:
        parsed = {}
    links = [r.get('detail_link', '') for r in parsed.get('list', [])]
    image_hosts = IMAGE_HOST in data
    check('ajax-data', status == 200 and h.get('content-type', '').lower() == 'application/json;charset=utf-8'
          and parsed.get('code') == 1 and links and all(l.startswith(prefix + 'index.php/vod/detail/') for l in links)
          and image_hosts == bool(w2) and 'vod_pwd' not in data and 'vod_play_url' not in data,
          {'status': status, 'rows': len(links), 'externalImages': image_hosts, 'firstLink': links[:1]})
    status, h, raw = fetch(port, prefix + 'index.php/ajax/suggest?mid=1&wd=' + urllib.parse.quote('星'))
    s = json.loads(raw.decode('utf-8')) if status == 200 else {}
    check('ajax-suggest', status == 200 and s.get('code') == 1 and s.get('url') == prefix + 'index.php/vod/search.html?wd=mac_wd'
          and h.get('cache-control') == 'no-store', {'status': status, 'url': s.get('url'), 'total': s.get('total')})
    # Provide：面向采集程序，W2 不适用（图片地址照旧输出）；缓存头按 cachetime
    status, h, raw = fetch(port, prefix + 'api.php/provide/vod/?ac=detail&ids=1')
    p = raw.decode('utf-8')
    check('provide-json', status == 200 and ct(h) == 'application/json;charset=utf-8'
          and h.get('x-content-type-options') == 'nosniff' and h.get('cache-control') == 'public, max-age=30'
          and IMAGE_HOST in p and 'vod_pwd' not in p, {'status': status, 'type': h.get('content-type'),
                                                      'cache': h.get('cache-control')})
    status, h, raw = fetch(port, prefix + 'api.php/provide/vod/at/xml/?ac=videolist')
    try:
        xml.dom.minidom.parseString(raw)
        wellformed = True
    except Exception:  # noqa: BLE001
        wellformed = False
    check('provide-xml', status == 200 and ct(h) == 'text/xml;charset=utf-8' and wellformed,
          {'status': status, 'wellFormed': wellformed})
    status, h, raw = fetch(port, prefix + 'api.php/provide/actor/?ac=list')
    check('provide-closed', status == 200 and raw == b'closed', {'status': status, 'body': raw[:20].decode()})
    status, h, raw = fetch(port, prefix + 'api.php/provide/vod/?ac=list', 'HEAD')
    check('provide-head', status == 200 and raw == b'' and h.get('content-length'), {'status': status})
    return out


def browser_checks(pw, origin, prefix, shots, full, result):
    browser = pw.chromium.launch()
    out = {'blocked': [], 'cspViolations': [], 'pages': {}}
    ctx = browser.new_context()

    def route(r):
        u = urllib.parse.urlsplit(r.request.url)
        if u.hostname in ('127.0.0.1', 'localhost') or u.scheme in ('data', 'blob'):
            r.continue_()
        else:
            out['blocked'].append(u.hostname or r.request.url[:40])
            r.abort()

    ctx.route('**/*', route)
    page = ctx.new_page()
    page.on('console', lambda m: out['cspViolations'].append(m.text[:200])
            if 'Content Security Policy' in m.text or 'Refused to' in m.text else None)
    for name, rel in HTML.items():
        page.goto(origin + prefix + rel, wait_until='load')
        page.wait_for_timeout(300)
        out['pages'][name] = {'title': page.title(),
                              'h1': page.evaluate("() => (document.querySelector('h1')||{}).textContent || ''")}
    if full:
        out['layout'] = layout_checks(ctx, origin + prefix, shots, result)
        out['keyboard'] = keyboard_checks(ctx, origin + prefix, result)
        out['reducedMotion'] = motion_checks(browser, origin + prefix, result)
    ctx.close()
    browser.close()
    out['blockedHosts'] = sorted(set(out['blocked']))
    del out['blocked']
    if out['blockedHosts']:
        result['failures'].append('browser external %s' % out['blockedHosts'])
    if out['cspViolations']:
        result['failures'].append('csp ' + json.dumps(out['cspViolations'][:5], ensure_ascii=False))
    return out


def layout_checks(ctx, base, shots, result):
    out = {}
    for width in (320, 360, 768, 1280):
        p = ctx.new_page()
        p.set_viewport_size({'width': width, 'height': 900})
        for name, rel in HTML.items():
            p.goto(base + rel, wait_until='load')
            over = p.evaluate('() => document.documentElement.scrollWidth - document.documentElement.clientWidth')
            out['%s@%d' % (name, width)] = over
            if over > 1:
                result['failures'].append('overflow %s@%d +%dpx' % (name, width, over))
            p.screenshot(path=str(shots / ('%s-%d.png' % (name, width))), full_page=True)
            # 长文本压力（MACC-55）：合成数据里没有超长片名/分类名，在浏览器内把标题与链接文字换成 120 个无空格字符后复测
            over = p.evaluate(STRESS)
            out['%s@%d stress' % (name, width)] = over
            if over > 1:
                result['failures'].append('overflow %s@%d stress +%dpx' % (name, width, over))
        p.close()
    return out


STRESS = """() => {const s='长'.repeat(40)+'W'.repeat(80);
    document.querySelectorAll('main h1, main h2, main a, main .notice').forEach(e => { e.textContent = s; });
    return document.documentElement.scrollWidth - document.documentElement.clientWidth}"""


def keyboard_checks(ctx, base, result):
    out = {}
    for name, rel in HTML.items():
        p = ctx.new_page()
        p.set_viewport_size({'width': 1280, 'height': 900})
        p.goto(base + rel, wait_until='load')
        seq = []
        for _ in range(14):
            p.keyboard.press('Tab')
            seq.append(p.evaluate("""() => {const e=document.activeElement, s=getComputedStyle(e);
                return {tag: e.tagName, cls: (e.className||'').toString().split(' ')[0],
                        text: (e.textContent||e.value||'').trim().slice(0,12),
                        outline: s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0 || s.boxShadow !== 'none'}}"""))
        out[name] = seq
        focusable = [x for x in seq if x['tag'] in ('A', 'INPUT', 'BUTTON')]
        if seq[0]['cls'] != 'skip-link' or not focusable or not all(x['outline'] for x in focusable):
            result['failures'].append('keyboard %s %s' % (name, json.dumps(seq[:4], ensure_ascii=False)))
        p.close()
    return out


def motion_checks(browser, base, result):
    """三页在 reduce 下不得有任何元素带动画或非零过渡；no-preference 下只记录（site.css 的动效都在 no-preference 媒体查询内）。"""
    out = {}
    for mode in ('reduce', 'no-preference'):
        c = browser.new_context(reduced_motion=mode)
        c.route('**/*', lambda r: r.continue_() if urllib.parse.urlsplit(r.request.url).hostname in (
            '127.0.0.1', 'localhost') else r.abort())
        p = c.new_page()
        for name, rel in HTML.items():
            p.goto(base + rel, wait_until='load')
            moving = p.evaluate("""() => {const m = matchMedia('(prefers-reduced-motion: reduce)').matches;
                const bad = [...document.querySelectorAll('*')].filter(e => {const s = getComputedStyle(e);
                    return s.animationName !== 'none' || s.transitionDuration.split(',').some(d => parseFloat(d) > 0)})
                    .map(e => e.tagName + '.' + (e.className || '').toString().split(' ')[0]);
                return {reduceMatches: m, moving: bad.slice(0, 5)}}""")
            out['%s %s' % (name, mode)] = moving
            if mode == 'reduce' and (not moving['reduceMatches'] or moving['moving']):
                result['failures'].append('reduced-motion %s %s' % (name, json.dumps(moving, ensure_ascii=False)))
        c.close()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    out_dir = (REPO / a.out).resolve()
    shots = out_dir / 'screenshots'
    shots.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='maccms-b06-site-check-'))
    legacy = legacy_config(work)
    from playwright.sync_api import sync_playwright
    db = Db()
    summary = {'started': datetime.now().isoformat(timespec='seconds'), 'database': db.name, 'runs': {}}
    db.create()
    proxy = Proxy(free_port())
    proxy.start()
    try:
        with sync_playwright() as pw:
            for sub in (False, True):
                for w1, w2 in ((0, 0), (0, 1), (1, 0), (1, 1)):
                    key = '%s W1W2=%d%d' % ('subdir' if sub else 'root', w1, w2)
                    base = 'http://127.0.0.1:%d/sub/' % proxy.port if sub else 'http://maccms.local.test/'
                    app = App(db, w1, w2, base, legacy, out_dir)
                    result = {'failures': []}
                    try:
                        if sub:
                            proxy.target = app.port
                            port, prefix, origin = proxy.port, '/sub/', 'http://127.0.0.1:%d' % proxy.port
                        else:
                            port, prefix, origin = app.port, '/', 'http://127.0.0.1:%d' % app.port
                        for part, fn in (('http', lambda: http_checks(port, prefix, w1, w2, result)),
                                         ('browser', lambda: browser_checks(pw, origin, prefix, shots,
                                                                            bool(not sub and w1 and w2), result))):
                            try:
                                result[part] = fn()
                            except Exception as e:  # noqa: BLE001 记录为失败，继续其他组
                                result['failures'].append('%s 异常 %s: %s' % (part, type(e).__name__, str(e)[:300]))
                    finally:
                        app.stop()
                    summary['runs'][key] = result
                    print(key, 'failures=%d' % len(result['failures']), flush=True)
    finally:
        proxy.server.shutdown()
        summary['databaseDropped'] = db.drop()
        shutil.rmtree(work, ignore_errors=True)
    summary['finished'] = datetime.now().isoformat(timespec='seconds')
    summary['failures'] = sum(len(r['failures']) for r in summary['runs'].values())
    (out_dir / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    for f in out_dir.glob('app-*.log'):
        f.unlink()
    print('failures', summary['failures'], 'dropped', summary['databaseDropped'])
    sys.exit(1 if summary['failures'] or not summary['databaseDropped'] else 0)


if __name__ == '__main__':
    main()
