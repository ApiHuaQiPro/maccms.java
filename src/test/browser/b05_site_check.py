"""B05（MACC-29）最终树的真实 HTTP 与浏览器检查。不进 ./mvnw verify；结果写入 --out 目录（summary.json、截图）。

流程：新建一次性库 maccms_it_<时间>_<随机>（导入 install.sql、site/play/seed.sql、site/module-search/seed.sql 与
b05_seed_extra.sql，全部合成数据）→ 依次以 8 组部署参数启动 target 中已打包的应用（W1/W2 = 00/01/10/11 × 根路径 /
子目录 /sub/；子目录由本脚本内置的本地反向代理把 /sub/ 映射到应用根路径，与 05 文档第 2 节的部署方式相同）→ 对每组：
  1. 真实 HTTP：各页面状态码、CSP、no-store、canonical 与站内链接前缀、外部主机是否按 W1/W2 出现、Logo、playerconfig.js；
  2. Chromium（Playwright，无头）：所有非本机请求在浏览器内被拦截并记录（不访问任何外部站点）；播放器内页在专用 CSP 下运行旧
     player.js 不触发 CSP 违规；试看计时到期停止并移除播放器（时钟快进）；
  3. 根路径 W1/W2=11 时另做：320/360/768/1280 宽度无横向溢出（截图）、键盘 Tab 顺序与可见焦点、prefers-reduced-motion。
→ 结束每个应用子进程（只按本脚本启动的 PID）→ 删除一次性库并确认已不存在。

管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL（jdbc:mysql://127.0.0.1:3306/）、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。
不连接 maccms_dev 或任何已有库，不改 nginx，不读仓库外配置。用法（仓库根目录）：
  python src/test/browser/b05_site_check.py --out docs/architecture/15-b05-site-check
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
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
BACKEND = REPO
LEGACY = REPO / 'php'  # 旧站 PHP（只读参照）
SECRET = 'synthetic-browser-check-secret-0123456789'
EXTERNAL_LOGO = 'https://logo.example.invalid/logo.png'
# 合成旧配置中的解析/预加载地址（play.parse、play.prestrain、vodplayer/voddowner 的 parse）
HOSTS_W1 = ('jx.example.invalid', 'jx2.example.invalid', 'dlparse.example.invalid', 'ad.example.invalid')
HOSTS_W2 = ('media.example.invalid', 'dl.example.invalid', 'cdn.example.invalid', 'player.example.invalid',
            'logo.example.invalid')
EXTERNAL = re.compile(r'[a-z0-9-]+\.example\.invalid')
RICH_TEXT_LINK = 'href="https://ok.example.invalid/x"'
STORED_PATHS = ('/upload/down/20.zip',)


def env(name):
    v = os.environ.get(name)
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
        self.run((BACKEND / 'src/test/resources/site/play/seed.sql').read_text(encoding='utf-8'), self.name)
        module = [l for l in (BACKEND / 'src/test/resources/site/module-search/seed.sql').read_text(encoding='utf-8')
                  .splitlines() if not re.match(r'(DELETE FROM mac_type|INSERT INTO mac_type|UPDATE mac_group)', l)]
        self.run('\n'.join(module) + '\n', self.name)
        self.run((BACKEND / 'src/test/browser/b05_seed_extra.sql').read_text(encoding='utf-8'), self.name)

    def drop(self):
        self.run('DROP DATABASE `%s`' % self.name)
        left = self.run("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='%s'" % self.name).strip()
        return left == '0'


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
                        if k.lower() == 'location' and v.startswith('/') and not v.startswith('/sub/'):
                            v = '/sub' + v
                        self.send_header(k, v)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                self.handle_one('GET')

            def do_POST(self):
                self.handle_one('POST')

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)

    def run(self):
        self.server.serve_forever()


class App:
    def __init__(self, db, w1, w2, base, logo, legacy_dir, log_dir):
        self.port = free_port()
        jar = next((BACKEND / 'target').glob('maccms-backend-*.jar'))
        args = ['java', '-jar', str(jar),
                '--spring.datasource.url=jdbc:mysql://%s:%s/%s?characterEncoding=UTF-8&useSSL=false' % (
                    db.host, db.port, db.name),
                '--spring.datasource.username=' + db.user, '--server.port=%d' % self.port,
                '--server.address=127.0.0.1', '--maccms.site.enabled=true',
                '--maccms.site.base-url=' + base, '--maccms.site.logo-url=' + logo,
                '--maccms.site.search.enabled=true', '--maccms.content.search.enabled=true',
                '--maccms.site.features.show=true', '--maccms.site.features.ajax-page=true',
                '--maccms.legacy-config.dir=' + str(legacy_dir), '--maccms.site.password-tickets.secret=' + SECRET,
                '--maccms.browser-external.w1=' + ('true' if w1 else 'false'),
                '--maccms.browser-external.w2=' + ('true' if w2 else 'false'),
                '--maccms.content.playback.trysee=1']
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
                c.request('GET', '/index.php')
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


def fetch(port, path, method='GET', body=None, headers=None):
    c = http.client.HTTPConnection('127.0.0.1', port, timeout=30)
    c.request(method, path, body=body, headers=headers or {})
    r = c.getresponse()
    return r.status, {k.lower(): v for k, v in r.getheaders()}, r.read().decode('utf-8', 'replace')


PAGES = {
    'home': '', 'type': 'index.php/vod/type/id/6.html', 'detail': 'index.php/vod/detail/id/1.html',
    'search': 'index.php/vod/search.html?wd=%E6%A0%B7%E6%9C%AC', 'show': 'index.php/vod/show/id/1.html',
    'vodsearch-positional': 'index.php/vodsearch/-------A------.html',
    # 视频 20（b05_seed_extra.sql）：权限完整、站内播放地址；视频 5：外部播放地址（W2 关时显示固定提示）；视频 6：多个播放组（第 1 组的
    # 播放器不在旧配置中，不是播放源）
    'play': 'index.php/vod/play/id/20/sid/1/nid/1.html', 'player': 'index.php/vod/player/id/20/nid/1/sid/1.html',
    'player-external': 'index.php/vod/player/id/5/nid/1/sid/1.html',
    'play-multi': 'index.php/vod/play/id/6/sid/2/nid/1.html',
    # 试看：视频 1 在只有试看权限的分类 6，第 3 集为站内地址（W2 关时也有播放器），时长取全局 user.trysee=1 分钟
    'trial-player': 'index.php/vod/player/id/1/nid/3/sid/1.html',
    'trial-external': 'index.php/vod/player/id/3/nid/1/sid/1.html',
    'pwd-player': 'index.php/vod/player/id/4/nid/1/sid/1.html',
    'down': 'index.php/vod/down/id/5/sid/1/nid/1.html', 'downer': 'index.php/vod/downer/id/5/nid/1/sid/1.html',
    'downer-local': 'index.php/vod/downer/id/20/nid/1/sid/1.html',
    'art': 'index.php/art/detail/id/50.html', 'art-p2': 'index.php/art/detail/id/50/page/2.html',
    'art-beyond': 'index.php/art/detail/id/50/page/9.html', 'art-pwd': 'index.php/art/detail/id/51.html',
    'art-search': 'index.php/art/search.html?wd=%E6%98%9F', 'art-show': 'index.php/art/show/id/21.html',
    'topic': 'index.php/topic/detail/id/1.html', 'actor': 'index.php/actor/detail/id/1.html',
    'role': 'index.php/role/detail/id/1.html', 'website': 'index.php/website/detail/id/1.html',
    'plot': 'index.php/plot/detail/id/101.html', 'art-index': 'index.php/art/index.html',
    'rss': 'index.php/vod/rss/id/1.html', 'ajax-detail': 'index.php/vod/ajax_detail/id/1.html',
    'ajax-search': 'index.php/art/ajax_search.html?wd=%E6%98%9F', 'playerconfig': 'static/js/playerconfig.js',
    # 返工第 1、2 项：角色筛选页（无分类）、模块随机页（分类 21 只有一个随机页，结果确定）、剧情搜索的视频键与多值 letter
    'role-show': 'index.php/role/show.html?letter=X%2CF', 'art-show-rnd': 'index.php/art/show/id/21.html?by=rnd',
    'plot-search': 'index.php/plot/search.html?letter=X%2CH&tid=1',
}
EXPECTED = {'pwd-player': 403, 'art-pwd': 403}


def http_checks(port, prefix, w1, w2, logo_external, result):
    out = {}
    for name, rel in PAGES.items():
        status, h, body = fetch(port, prefix + rel)
        csp = h.get('content-security-policy', '')
        # 富文本中的普通超链接是导航目标，不是浏览器加载的来源（W1/W2 只管解析/外部脚本与外链图片、播放、下载地址）；
        # 合成数据只有文章 50 第 3 页的 ok.example.invalid 一处，单独记录，不计入外部来源
        nav = RICH_TEXT_LINK in body
        hosts = sorted(set(EXTERNAL.findall(body.replace(RICH_TEXT_LINK, ''))) | set(EXTERNAL.findall(csp)))
        allowed = set()
        if w1:
            allowed |= set(HOSTS_W1)
        if w2:
            allowed |= set(HOSTS_W2)
        bad = [x for x in hosts if x not in allowed]
        links = re.findall(r'(?:href|src|action)="(/[^"]*)"', body)
        # 数据中存的站内绝对路径（下载地址 /upload/down/20.zip）按旧版原样输出，不随部署子目录改写；单独记录，不算生成链接的错误
        stored = [l for l in links if l in STORED_PATHS]
        wrong_prefix = [l for l in links if not l.startswith(prefix) and l not in STORED_PATHS]
        out[name] = {'richTextNavigationLink': nav, 'storedAbsolutePaths': stored, 'status': status, 'cache': h.get('cache-control'), 'csp': csp[:160], 'externalHosts': hosts,
                     'unexpectedHosts': bad, 'linksOutsidePrefix': wrong_prefix[:5],
                     'logo': 'site-brand__logo' in body}
        ok = status == EXPECTED.get(name, 200) and not bad and not wrong_prefix
        if name not in ('rss', 'ajax-detail', 'ajax-search', 'playerconfig', 'player', 'trial-player', 'trial-external',
                        'pwd-player', 'player-external'):
            ok = ok and (('site-brand__logo' in body) == (w2 or not logo_external))
        if not w1 and not w2:
            ok = ok and not hosts
        ok = ok and ('合成站' in body or name in ('playerconfig', 'ajax-detail', 'ajax-search'))
        out[name]['ok'] = ok
        if not ok:
            result['failures'].append('http %s %s' % (name, json.dumps(out[name], ensure_ascii=False)[:400]))
    # 密码：只接受 POST，签名票据后 303 回到播放器内页并可见；错误密码 403 且不回显
    status, h, body = fetch(port, prefix + PAGES['pwd-player'], 'POST', 'pwd=wrong',
                            {'Content-Type': 'application/x-www-form-urlencoded'})
    good = fetch(port, prefix + PAGES['pwd-player'], 'POST', 'pwd=Ab9',
                 {'Content-Type': 'application/x-www-form-urlencoded'})
    cookie = good[1].get('set-cookie', '')
    after = fetch(port, prefix + PAGES['pwd-player'], headers={'Cookie': cookie.split(';')[0]}) if cookie else (0, {}, '')
    out['password'] = {'wrongStatus': status, 'wrongEcho': 'wrong' in body, 'goodStatus': good[0],
                       'cookieFlags': [f.strip().split('=')[0] for f in cookie.split(';')[1:]],
                       'afterStatus': after[0]}
    if not (status in (403, 200) and 'wrong' not in body and good[0] == 303 and 'HttpOnly' in cookie
            and 'SameSite=Lax' in cookie and after[0] == 200):
        result['failures'].append('password ' + json.dumps(out['password'], ensure_ascii=False))
    return out


def browser_checks(pw, origin, prefix, w1, w2, shots, full, result):
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
    for name in ('home', 'play', 'player', 'player-external', 'play-multi', 'down', 'downer', 'art', 'search',
                 'art-search', 'show', 'actor'):
        page.goto(origin + prefix + PAGES[name], wait_until='load')
        page.wait_for_timeout(500)
        info = {'title': page.title()}
        if name == 'player':
            info['stage'] = page.evaluate("""() => {const s=document.getElementById('player-stage');
                return {children: s ? s.children.length : -1, html: s ? s.innerHTML.slice(0, 200) : '',
                        iframes: document.querySelectorAll('iframe').length,
                        playerVar: typeof window.player_aaaa, macPlayer: typeof window.MacPlayer}}""")
            if info['stage']['playerVar'] != 'object' or info['stage']['macPlayer'] != 'object':
                result['failures'].append('browser player.js 未在 CSP 下运行 ' + json.dumps(info, ensure_ascii=False))
        if name == 'player-external':
            info['notice'] = page.evaluate("() => (document.body.innerText || '').slice(0, 120)")
            info['stagePresent'] = page.query_selector('#player-stage') is not None
            if info['stagePresent'] != bool(w2):
                result['failures'].append('browser W2 外部播放 ' + json.dumps(info, ensure_ascii=False))
        out['pages'][name] = info
    # 试看：播放器内页按 data-trial-minutes 计时，到期停止并移除挂载内容（时钟快进，浏览器计时边界）
    trial = ctx.new_page()
    trial.clock.install()
    trial.goto(origin + prefix + PAGES['trial-player'], wait_until='load')
    stage = trial.query_selector('#player-stage')
    minutes = stage.get_attribute('data-trial-minutes') if stage else None
    before = trial.evaluate("() => document.querySelectorAll('#player-stage iframe,#player-stage video').length")
    trial.clock.fast_forward(int(minutes or '0') * 60 * 1000 + 3000)
    trial.wait_for_timeout(300)
    t = trial.evaluate("""() => {const m=document.getElementById('trial-message'), s=document.getElementById('player-stage');
        return {hidden: m ? m.hidden : null, text: m ? m.textContent : '',
                media: s ? s.querySelectorAll('iframe,video,audio,object,embed').length : -1}}""")
    out['trial'] = dict(t, minutes=minutes, mediaBefore=before)
    if not (minutes and minutes != '0' and before > 0 and t['hidden'] is False and t['media'] == 0 and t['text']):
        result['failures'].append('trial ' + json.dumps(out['trial'], ensure_ascii=False))
    if full:
        out['layout'] = layout_checks(ctx, origin + prefix, shots, result)
        out['keyboard'] = keyboard_checks(ctx, origin + prefix, result)
        out['reducedMotion'] = motion_checks(browser, origin + prefix, result)
    ctx.close()
    browser.close()
    expected_blocked = set()
    if w1:
        expected_blocked |= set(HOSTS_W1)
    if w2:
        expected_blocked |= set(HOSTS_W2) | {'ok.example.invalid'}
    unexpected = sorted(set(h for h in out['blocked'] if h not in expected_blocked))
    out['blockedHosts'] = sorted(set(out['blocked']))
    out['unexpectedBlocked'] = unexpected
    del out['blocked']
    if unexpected or (not w1 and not w2 and out['blockedHosts']):
        result['failures'].append('browser external %s' % out['blockedHosts'])
    if out['cspViolations']:
        result['failures'].append('csp ' + json.dumps(out['cspViolations'][:5], ensure_ascii=False))
    return out


def layout_checks(ctx, base, shots, result):
    out = {}
    for width in (320, 360, 768, 1280):
        p = ctx.new_page()
        p.set_viewport_size({'width': width, 'height': 900})
        for name in ('home', 'search', 'show', 'detail', 'play', 'down', 'art', 'art-search', 'actor', 'website',
                     'art-pwd', 'role-show', 'art-show-rnd'):
            p.goto(base + PAGES[name], wait_until='load')
            over = p.evaluate('() => document.documentElement.scrollWidth - document.documentElement.clientWidth')
            out['%s@%d' % (name, width)] = over
            if over > 1:
                result['failures'].append('overflow %s@%d +%dpx' % (name, width, over))
            if name in ('home', 'search', 'play', 'art'):
                p.screenshot(path=str(shots / ('%s-%d.png' % (name, width))), full_page=False)
        p.close()
    return out


def keyboard_checks(ctx, base, result):
    out = {}
    for name in ('home', 'search', 'art', 'play', 'art-search', 'art-pwd'):
        p = ctx.new_page()
        p.set_viewport_size({'width': 1280, 'height': 900})
        p.goto(base + PAGES[name], wait_until='load')
        seq = []
        for _ in range(12):
            p.keyboard.press('Tab')
            seq.append(p.evaluate("""() => {const e=document.activeElement, s=getComputedStyle(e);
                return {tag: e.tagName, cls: (e.className||'').toString().split(' ')[0], text: (e.textContent||e.value||'').trim().slice(0,12),
                        outline: s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0 || s.boxShadow !== 'none'}}"""))
        out[name] = seq
        if seq[0]['cls'] != 'skip-link' or not all(x['outline'] for x in seq if x['tag'] in ('A', 'INPUT', 'BUTTON')):
            result['failures'].append('keyboard %s %s' % (name, json.dumps(seq[:4], ensure_ascii=False)))
        p.keyboard.press('Shift+Tab')
        p.close()
    return out


def motion_checks(browser, base, result):
    out = {}
    for mode in ('reduce', 'no-preference'):
        c = browser.new_context(reduced_motion=mode)
        c.route('**/*', lambda r: r.continue_() if urllib.parse.urlsplit(r.request.url).hostname in (
            '127.0.0.1', 'localhost') else r.abort())
        p = c.new_page()
        p.goto(base + PAGES['home'], wait_until='load')
        out[mode] = p.evaluate("""() => {const e=document.querySelector('.video-card__image');
            return e ? getComputedStyle(e).transitionDuration : null}""")
        c.close()
    if out.get('reduce') != '0s' or out.get('no-preference') in ('0s', None):
        result['failures'].append('reduced-motion ' + json.dumps(out))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    out_dir = (REPO / a.out).resolve()
    shots = out_dir / 'screenshots'
    shots.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='maccms-b05-site-check-'))
    legacy = work / 'legacy-config'
    shutil.copytree(BACKEND / 'src/test/resources/site/play/legacy-config', legacy)
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
                    # 子目录：Logo 为 base-url 同主机的绝对地址（W2 关时仍输出）；根路径：外站 Logo（只在 W2 开时输出）
                    logo = base + 'static/site/logo.png' if sub else EXTERNAL_LOGO
                    app = App(db, w1, w2, base, logo, legacy, out_dir)
                    result = {'failures': []}
                    try:
                        if sub:
                            proxy.target = app.port
                            port, prefix, origin = proxy.port, '/sub/', 'http://127.0.0.1:%d' % proxy.port
                        else:
                            port, prefix, origin = app.port, '/', 'http://127.0.0.1:%d' % app.port
                        for part, fn in (('http', lambda: http_checks(port, prefix, w1, w2, not sub, result)),
                                         ('browser', lambda: browser_checks(pw, origin, prefix, w1, w2, shots,
                                                                            bool(not sub and w1 and w2), result))):
                            try:
                                result[part] = fn()
                            except Exception as e:  # 记录为失败，继续其他组
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
    print('failures', summary['failures'], 'dropped', summary['databaseDropped'])
    sys.exit(1 if summary['failures'] or not summary['databaseDropped'] else 0)


if __name__ == '__main__':
    main()
