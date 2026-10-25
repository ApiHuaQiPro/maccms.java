"""B12 后台浏览器验收（docs/architecture/21-b12-admin-framework.md 第 10 节）：对一个运行中的新后台，用 Playwright 的本机 Chromium
无头模式走一遍真实页面：

  - 登录页 → 输入账号密码（旧版 layui 表单 + login.js）→ 进入框架页；
  - 框架页：layui 导航、侧栏菜单渲染；点"管理员"菜单在标签页 iframe 中打开列表；
  - 管理员列表：layui 开关（状态）渲染、分页渲染；打开"添加"弹层（j-iframe），在表单里勾权限、保存，列表出现新管理员；
  - 锁屏：点"锁屏操作"出现锁屏层，输入错误密码提示"密码错误"，正确密码解锁；
  - 退出登录回到登录页；
  - 全程收集内容安全策略违规（securitypolicyviolation）、页面脚本错误与控制台 error，以及对外部主机的请求——都必须为 0；
  - 输出各步截图与 report.json。

不在 ./mvnw verify 中运行；只访问给定的本机地址。
用法：python b12_admin_check.py <后台入口地址，如 http://127.0.0.1:18290/_admin.php> <账号> <密码> <输出目录>
"""

import json
import os
import sys
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


def main():
    if len(sys.argv) != 5:
        print(__doc__)
        sys.exit(2)
    entry, account, password, out = sys.argv[1:]
    os.makedirs(out, exist_ok=True)
    origin = '{0.scheme}://{0.netloc}'.format(urlparse(entry))
    report = {'steps': [], 'csp': [], 'pageErrors': [], 'consoleErrors': [], 'external': []}

    def step(name, ok, detail=''):
        report['steps'].append({'step': name, 'ok': bool(ok), 'detail': detail})
        print(('OK   ' if ok else 'FAIL ') + name + (' — ' + detail if detail else ''))

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 900})

        def watch(page):
            page.on('pageerror', lambda e: report['pageErrors'].append(str(e)))
            page.on('console', lambda m: report['consoleErrors'].append(m.text) if m.type == 'error' else None)

        context.on('page', watch)
        context.on('request', lambda r: report['external'].append(r.url)
                   if not r.url.startswith(origin) and not r.url.startswith('data:')
                   and not r.url.startswith('about:') else None)
        context.add_init_script("""
            document.addEventListener('securitypolicyviolation', function (e) {
                (window.top.__csp = window.top.__csp || []).push(e.violatedDirective + ' ' + e.blockedURI);
            });
        """)
        page = context.new_page()
        watch(page)

        # ---------------- 登录
        page.goto(entry + '/admin/index/login.html')
        page.wait_for_load_state('networkidle')
        step('登录页 layui 表单', page.locator('form.layui-form input[name=admin_name]').count() == 1)
        page.screenshot(path=os.path.join(out, '01-login.png'))
        page.fill('input[name=admin_name]', account)
        page.fill('input[name=admin_pwd]', 'wrong-password')
        page.click('.btn-submit')
        try:
            page.wait_for_selector('.layui-layer-msg:has-text("账号或密码错误")', timeout=5000)
            step('密码错误提示', True)
        except Exception:
            step('密码错误提示', False, page.inner_text('body')[:200])
        page.fill('input[name=admin_pwd]', password)
        page.click('.btn-submit')
        page.wait_for_url('**/admin/index/index.html', timeout=10000)
        page.wait_for_load_state('networkidle')
        step('登录后进入框架页', page.url.endswith('/admin/index/index.html'))

        # ---------------- 框架页
        menus = page.locator('#navBar .admin-nav-item').count()
        step('侧栏菜单', menus > 10, '%d 项' % menus)
        welcome = page.frame_locator('iframe[lay-id="111"]')
        step('欢迎页 iframe', welcome.locator('table.layui-table').count() == 1)
        page.screenshot(path=os.path.join(out, '02-frame.png'))

        page.locator('.main-nav a', has_text='用户').first.click()
        page.locator('#navBar .admin-nav-item', has_text='管理员').first.click()
        page.wait_for_selector('#B_history li:nth-child(2)', timeout=5000)
        tab_id = page.locator('#B_history li.layui-this').get_attribute('lay-id')
        lst = page.frame_locator('iframe[lay-id="%s"]' % tab_id)
        lst.locator('table.layui-table tbody tr').first.wait_for(timeout=10000)
        rows = lst.locator('table.layui-table tbody tr').count()
        switches = lst.locator('.layui-form-switch').count()
        step('管理员列表标签页', rows >= 1, '%d 行' % rows)
        step('layui 状态开关已渲染', switches == rows, '%d 个' % switches)
        step('layui 分页已渲染', lst.locator('#pages .layui-laypage').count() == 1)
        page.screenshot(path=os.path.join(out, '03-admin-list.png'))

        # 添加管理员：列表页里的 j-iframe 打开弹层
        lst.locator('a.j-iframe', has_text='添加').click()
        list_frame = page.frame(url=lambda u: '/admin/admin/index' in u)
        list_frame.wait_for_selector('.layui-layer-iframe iframe', timeout=5000)
        form_frame = None
        for _ in range(50):
            for f in page.frames:
                if '/admin/admin/info' in f.url:
                    form_frame = f
            if form_frame:
                break
            page.wait_for_timeout(100)
        form_frame.wait_for_selector('.role-list-form .layui-form-checkbox', timeout=10000)
        boxes = form_frame.locator('.role-list-form .layui-form-checkbox').count()
        step('权限树复选框已渲染', boxes > 100, '%d 个' % boxes)
        page.screenshot(path=os.path.join(out, '04-admin-form.png'))
        form_frame.fill('input[name=admin_name]', 'browser01')
        form_frame.fill('input[name=admin_pwd]', 'browser-pass')
        form_frame.locator('input[name=admin_status][value="1"] + .layui-form-radio').click()
        # 勾选"视频数据"（vod/data）
        form_frame.locator('.role-list-form .layui-form-checkbox', has_text='视频数据').first.click()
        form_frame.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2500)
        lst2 = page.frame(url=lambda u: '/admin/admin/index' in u)
        names = lst2.inner_text('table.layui-table tbody') if lst2 else ''
        step('保存后列表出现新管理员', 'browser01' in names)
        page.screenshot(path=os.path.join(out, '05-admin-saved.png'))

        # ---------------- 锁屏
        # 两个 #lockScreen（下拉菜单与窄屏菜单）都是隐藏的；旧 admin_common.js 用 $('#lockScreen') 绑定第一个
        page.locator('#lockScreen').first.dispatch_event('click')
        page.wait_for_selector('.lock-screen', timeout=5000)
        # 旧 admin_common.js 对 #lockScreen 重复绑定了两次（第二段注释写着"清理缓存"），点一次弹出两层，与旧版相同
        step('锁屏层', page.locator('.lock-screen').count() >= 1, '%d 层' % page.locator('.lock-screen').count())
        page.locator('.unlockedPwd').last.fill('bad')
        page.locator('.unlocked').last.click()
        page.wait_for_timeout(800)
        step('锁屏错误密码提示', '密码错误' in page.locator('.unlockTips').last.inner_text())
        page.screenshot(path=os.path.join(out, '06-locked.png'))
        page.locator('.unlockedPwd').last.fill(password)
        page.locator('.unlocked').last.click()
        page.wait_for_timeout(1000)
        step('解锁', page.locator('.lock-screen').count() == 0)

        # ---------------- 退出
        page.goto(entry + '/admin/index/logout.html')
        page.wait_for_load_state('networkidle')
        step('退出后回到登录页', page.url.endswith('/admin/index/login.html'))
        page.goto(entry + '/admin/index/welcome.html')
        step('退出后旧会话无效', page.url.endswith('/admin/index/login.html'))

        report['csp'] = page.evaluate('window.__csp || []')
        browser.close()

    step('无内容安全策略违规', not report['csp'], '; '.join(report['csp'][:5]))
    step('无页面脚本错误', not report['pageErrors'], '; '.join(report['pageErrors'][:5]))
    step('无控制台错误', not report['consoleErrors'], '; '.join(report['consoleErrors'][:5]))
    step('无外部主机请求', not report['external'], '; '.join(report['external'][:5]))
    with open(os.path.join(out, 'report.json'), 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    sys.exit(0 if all(s['ok'] for s in report['steps']) else 1)


if __name__ == '__main__':
    main()
