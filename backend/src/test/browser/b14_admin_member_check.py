"""B14 后台会员与财务管理浏览器验收（docs/architecture/23-b14-admin-member.md 第 9 节）：对一个运行中的新后台（可写数据源打开，库里是
b14_seed.sql 的合成数据），用 Playwright 的本机 Chromium 无头模式走一遍真实页面：

  - 登录 → 会员列表（layui 表格、分页、状态开关写库）；
  - 会员"添加"弹层：layui 下拉、laydate 日期控件；选 VIP 组后到期时间一栏显示；保存后库里是 md5(密码)、组与到期时间；
  - 会员"编辑"弹层：改积分、密码留空保存，密码不变；行内删除（confirm 层）；推广会员页的三级人数与奖励合计；
  - 会员头像：编辑弹层点"上传图片"选图 → upload/upload?flag=user&user_id=1 成功、地址回填、文件是 100×100 JPEG、登记附件、上传本身不改
    user_portrait；悬停预览从 /upload/ 读到新头像（由前面的 nginx 提供）；保存后 user_portrait 写库；重新打开表单是新地址；伪装图片被拒绝且
    原头像不变；不存在或非法的会员号失败、不落盘；
  - 会员组：添加弹层"全选"后保存（权限 JSON 与分类串）、编辑、状态开关、删除有会员的组得到提示且不删；
  - 充值卡：生成弹层生成 5 张（16 位卡号、8 位卡密）、"导出"按钮经隐藏 iframe 下载 CSV（文件名、表头、行数）、"清空"；
  - 订单列表与行内删除；提现：行内"审核"（冻结积分扣减）、勾选"删除"未审核的提现（冻结积分退回）；
  - 积分日志、访问日志、推广访问列表与删除；
  - 全程收集内容安全策略违规、页面脚本错误、控制台 error、对外部主机的请求与 4xx/5xx 响应——都必须为 0；
  - 输出各步截图与 report.json。

库内核对经 mysql 命令行只读查询（一次性库 maccms_it_*）。不在 ./mvnw verify 中运行；只访问给定的本机地址。
用法：python b14_admin_member_check.py <后台入口地址，如 http://127.0.0.1:18299/_admin.php> <账号> <密码> <输出目录> <一次性库名>
      <站点根目录（其下 upload/，与 maccms.member.upload-dir 相同）> <上传用的样例图片>
环境变量 MYSQL_LOCAL_USER / MYSQL_LOCAL_PW（本机开发库账号，只用于只读核对）。
"""

import hashlib
import json
import os
import re
import subprocess
import sys
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


def jpeg_size(path):
    """JPEG 文件的（宽, 高）；不是 JPEG 或没有这个文件时为 None。"""
    if not os.path.isfile(path):
        return None
    data = open(path, 'rb').read()
    if data[:2] != bytes([0xff, 0xd8]):
        return None
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xff:
            return None
        marker = data[i + 1]
        length = int.from_bytes(data[i + 2:i + 4], 'big')
        if marker in (0xc0, 0xc1, 0xc2):
            return int.from_bytes(data[i + 7:i + 9], 'big'), int.from_bytes(data[i + 5:i + 7], 'big')
        i += 2 + length
    return None


def main():
    if len(sys.argv) != 8:
        print(__doc__)
        sys.exit(2)
    entry, account, password, out, db, site, sample = sys.argv[1:]
    if not re.fullmatch(r'maccms_it_[0-9]{14}_[0-9a-f]{8}', db):
        print('只能核对一次性库 maccms_it_*')
        sys.exit(2)
    os.makedirs(out, exist_ok=True)
    origin = '{0.scheme}://{0.netloc}'.format(urlparse(entry))
    report = {'steps': [], 'csp': [], 'pageErrors': [], 'consoleErrors': [], 'external': [], 'responses4xx5xx': []}
    shots = [0]

    def step(name, ok, detail=''):
        report['steps'].append({'step': name, 'ok': bool(ok), 'detail': str(detail)})
        print(('OK   ' if ok else 'FAIL ') + name + (' — ' + str(detail) if detail else ''), flush=True)
        return ok

    def sql(q):
        r = subprocess.run(['mysql', '-h127.0.0.1', '-u' + os.environ.get('MYSQL_LOCAL_USER', 'root'),
                            '-p' + os.environ['MYSQL_LOCAL_PW'], '--default-character-set=utf8', '-N', '-B', db],
                           input=q + ';\n', capture_output=True, text=True, encoding='utf-8')
        if r.returncode != 0:
            raise RuntimeError('mysql: ' + r.stderr.strip().splitlines()[-1])
        return [line.split('\t') for line in r.stdout.splitlines()]

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 900}, accept_downloads=True)

        def watch(pg):
            pg.on('pageerror', lambda e: report['pageErrors'].append(str(e)))
            pg.on('console', lambda m: report['consoleErrors'].append(m.text) if m.type == 'error' else None)

        context.on('page', watch)
        context.on('request', lambda r: report['external'].append(r.url)
                   if not r.url.startswith(origin) and not r.url.startswith('data:')
                   and not r.url.startswith('about:') and not r.url.startswith('blob:') else None)
        context.on('response', lambda r: report['responses4xx5xx'].append('%d %s' % (r.status, r.url))
                   if r.status >= 400 else None)
        context.add_init_script("""
            document.addEventListener('securitypolicyviolation', function (e) {
                (window.top.__csp = window.top.__csp || []).push(e.violatedDirective + ' ' + e.blockedURI);
            });
        """)
        page = context.new_page()
        watch(page)

        def shot(name):
            shots[0] += 1
            page.screenshot(path=os.path.join(out, '%02d-%s.png' % (shots[0], name)))

        def frame(part, timeout=10000):
            """url 含 part 的最后一个 frame（弹层 iframe）。"""
            for _ in range(timeout // 100):
                found = [f for f in page.frames if part in f.url]
                if found:
                    return found[-1]
                page.wait_for_timeout(100)
            raise RuntimeError('frame not found: ' + part)

        def settle(ms=800):
            page.wait_for_load_state('load')
            page.wait_for_timeout(ms)

        def open_list(path):
            page.goto(entry + path)
            settle()

        def confirm_layer():
            page.locator('.layui-layer-dialog .layui-layer-btn0').first.click()
            page.wait_for_timeout(1500)

        def confirm_then_reload():
            # j-ajax、j-page-btns：提示框关闭（layer.msg 默认 3 秒）后整页刷新（旧 admin_common.js）
            with page.expect_navigation(timeout=15000):
                page.locator('.layui-layer-dialog .layui-layer-btn0').first.click()
            settle()

        def choose(scope, name, text):
            scope.locator('select[name=%s] + .layui-form-select .layui-select-title' % name).first.click()
            scope.locator('select[name=%s] + .layui-form-select dd' % name, has_text=text).first.click()

        def submit_child(f):
            # data-child="true" 的表单：保存成功后父页面刷新、弹层关闭（旧 admin_common.js）
            f.locator('button[lay-filter=formSubmit]').click()
            page.wait_for_timeout(2500)
            settle(1000)

        # ---------------- 登录
        page.goto(entry + '/admin/index/login.html')
        settle()
        page.fill('input[name=admin_name]', account)
        page.fill('input[name=admin_pwd]', password)
        page.click('.btn-submit')
        page.wait_for_url('**/admin/index/index.html', timeout=10000)
        step('登录后进入框架页', page.url.endswith('/admin/index/index.html'))

        # ---------------- 会员列表与状态开关
        open_list('/admin/user/data.html')
        rows = page.locator('table.layui-table tbody tr').count()
        step('会员列表 layui 表格（4 个会员）', rows == 4, rows)
        step('分页已渲染', page.locator('#pages .layui-laypage').count() == 1)
        step('第 1 页执行到期降级（会员 3 已到期）', sql('SELECT group_id FROM mac_user WHERE user_id = 3') == [['2']])
        shot('user-list')
        page.locator('tr:has-text("daveuser") .layui-form-switch').click()
        page.wait_for_timeout(1500)
        step('状态开关写库（会员 4 启用）', sql('SELECT user_status FROM mac_user WHERE user_id = 4') == [['1']])

        # ---------------- 添加会员（layui 下拉、laydate；VIP 组显示到期时间）
        page.locator('a.j-iframe', has_text='添加').first.click()
        f = frame('/admin/user/info')
        f.wait_for_selector('.layui-form-select', timeout=8000)
        step('会员表单 layui 下拉已渲染', f.locator('.layui-form-select').count() == 1)
        step('默认会员组时到期时间一栏隐藏', not f.locator('.rowTime').is_visible())
        f.fill('#user_name', 'browser01')
        f.fill('#user_pwd', 'secret 1')
        choose(f, 'group_id', 'VIP会员')
        page.wait_for_timeout(300)
        step('选 VIP 组后到期时间一栏显示', f.locator('.rowTime').is_visible())
        f.click('#user_end_time')
        f.wait_for_selector('.layui-laydate', timeout=5000)
        step('laydate 日期控件弹出', f.locator('.layui-laydate').count() >= 1)
        f.locator('.layui-laydate .laydate-btns-confirm').click()
        f.fill('#user_end_time', '2031-02-03 04:05:06')
        f.fill('#user_points', '88')
        shot('user-add')
        submit_child(f)
        u = sql("SELECT user_pwd, group_id, user_end_time, user_points FROM mac_user WHERE user_name = 'browser01'")
        step('新增会员写库（md5(原值)、VIP 组、到期时间、积分）',
             u == [[hashlib.md5('secret 1'.encode()).hexdigest(), '3', '1927829106', '88']], u)
        step('保存后列表出现新会员', 'browser01' in page.inner_text('table.layui-table tbody'))

        # ---------------- 编辑会员：改积分，密码留空不改
        before = sql('SELECT user_pwd FROM mac_user WHERE user_id = 1')
        page.locator('tr:has-text("alice01")').locator('a.j-iframe', has_text='编辑').click()
        f = frame('/admin/user/info')
        f.wait_for_selector('#user_points', timeout=8000)
        step('编辑表单密码框为空', f.input_value('#user_pwd') == '')
        step('编辑已有会员显示头像一栏', f.locator('#user_portrait').count() == 1)
        f.fill('#user_points', '321')
        submit_child(f)
        after = sql('SELECT user_pwd, user_points FROM mac_user WHERE user_id = 1')
        step('编辑保存：积分写库、密码留空不改', after == [[before[0][0], '321']], after)

        # ---------------- 会员头像：编辑表单的"上传图片" → 预览 → 保存 → 重新打开（23 文档第 7.7 节）
        portrait = os.path.join(site, 'upload', 'user', '1', '1.jpg')
        step('上传前没有头像文件，user_portrait 为空', not os.path.exists(portrait)
             and sql('SELECT user_portrait FROM mac_user WHERE user_id = 1') == [['']])
        page.locator('tr:has-text("alice01")').locator('a.j-iframe', has_text='编辑').click()
        f = frame('/admin/user/info')
        f.wait_for_selector('#upload1', timeout=8000)
        with page.expect_response(lambda r: '/admin/upload/upload' in r.url and 'flag=user' in r.url, timeout=15000) as up:
            with page.expect_file_chooser() as fc:
                f.click('#upload1')
            fc.value.set_files(sample)
        reply = up.value.json()
        step('按钮上传：地址带 flag=user&user_id=1，返回成功', 'flag=user&user_id=1' in up.value.url and reply.get('code') == 1
             and reply['data']['file'] == 'upload/user/1/1.jpg', json.dumps(reply, ensure_ascii=False)[:200])
        f.wait_for_function("document.querySelector('#user_portrait').value === 'upload/user/1/1.jpg'", timeout=8000)
        step('头像地址回填到表单', f.input_value('#user_portrait') == 'upload/user/1/1.jpg')
        size = jpeg_size(portrait)
        step('头像文件是 100×100 的 JPEG（重新编码）', size == (100, 100), size)
        annex = sql("SELECT annex_type FROM mac_annex WHERE annex_file = 'upload/user/1/1.jpg'")
        step('头像登记附件（一行）', annex == [['image']], annex)
        step('上传本身不改会员表（同旧版），保存前 user_portrait 仍为空',
             sql('SELECT user_portrait FROM mac_user WHERE user_id = 1') == [['']])
        f.hover('#user_portrait')
        f.wait_for_function("(function(){var i=document.querySelector('.showpic_img');"
                            "return i && i.complete && i.naturalWidth === 100 && i.src.indexOf('/upload/user/1/1.jpg') >= 0;})()",
                            timeout=8000)
        step('悬停预览：从 /upload/ 读到新头像', f.locator('.showpic').is_visible())
        shot('user-portrait-uploaded')
        page.mouse.move(5, 5)
        submit_child(f)
        step('保存表单后 user_portrait 写库', sql('SELECT user_portrait FROM mac_user WHERE user_id = 1') == [['upload/user/1/1.jpg']])
        page.locator('tr:has-text("alice01")').locator('a.j-iframe', has_text='编辑').click()
        f = frame('/admin/user/info')
        f.wait_for_selector('#user_portrait', timeout=8000)
        step('重新打开表单：头像一栏是新地址', f.input_value('#user_portrait') == 'upload/user/1/1.jpg')
        shot('user-portrait-reopened')
        digest = hashlib.sha256(open(portrait, 'rb').read()).hexdigest()
        # 伪装图片（扩展名 .jpg、内容不是图片）：提示"文件上传失败！"，原头像文件不变、不新增附件
        fake = os.path.join(out, 'fake.jpg')
        with open(fake, 'w', encoding='utf-8') as fh:
            fh.write('not an image ' * 40)
        with page.expect_response(lambda r: '/admin/upload/upload' in r.url and 'flag=user' in r.url, timeout=15000) as up:
            with page.expect_file_chooser() as fc:
                f.click('#upload1')
            fc.value.set_files(fake)
        f.wait_for_selector('.layui-layer-msg:has-text("文件上传失败")', timeout=8000)
        step('伪装图片被拒绝：提示失败，原头像文件与附件不变', up.value.json().get('code') == 0
             and hashlib.sha256(open(portrait, 'rb').read()).hexdigest() == digest
             and sql("SELECT COUNT(*) FROM mac_annex WHERE annex_file LIKE 'upload/user/%'") == [['1']]
             and f.input_value('#user_portrait') == 'upload/user/1/1.jpg')
        # 不存在的会员号、非法会员号（同源的脚本请求，带后台会话）：失败，不落盘
        bad = f.evaluate("""async (ids) => {
            const out = [];
            for (const id of ids) {
                const fd = new FormData();
                fd.append('file', new Blob([new Uint8Array([137, 80, 78, 71])], {type: 'image/png'}), 'a.png');
                const r = await fetch(ADMIN_PATH + '/admin/upload/upload.html?flag=user&user_id=' + id, {method: 'POST', body: fd,
                    headers: {'X-Requested-With': 'XMLHttpRequest'}});
                out.push((await r.json()).msg);
            }
            return out;
        }""", ['999', '0', '..%2F..%2F1', 'abc'])
        step('不存在或非法的会员号：文件上传失败、不落盘', bad == ['文件上传失败！'] * 4
             and sorted(os.listdir(os.path.join(site, 'upload', 'user'))) == ['1'], bad)
        open_list('/admin/user/data.html')

        # ---------------- 行内删除会员
        page.locator('tr:has-text("browser01") a.j-tr-del').click()
        confirm_layer()
        step('行内删除会员', sql("SELECT COUNT(*) FROM mac_user WHERE user_name = 'browser01'") == [['0']])

        # ---------------- 推广会员页
        open_list('/admin/user/reward.html?uid=1')
        text = page.inner_text('.layui-btn-group')
        step('推广会员页：一级 2 人、二级 1 人、一级奖励 5 积分', '一级分销总人数【2】' in text and '二级分销总人数【1】' in text
             and '总提成积分【5】' in text, text.replace('\n', ' '))
        shot('user-reward')

        # ---------------- 会员组
        open_list('/admin/group/index.html')
        step('会员组列表（4 组）', page.locator('table.layui-table tbody tr').count() == 4)
        page.locator('a.j-iframe', has_text='添加').first.click()
        f = frame('/admin/group/info')
        f.wait_for_selector('.role-list-form', timeout=8000)
        f.fill('input[name=group_name]', '浏览器组')
        f.locator('.formCheckAll').click()
        shot('group-add')
        submit_child(f)
        g = sql("SELECT group_status, group_type, group_popedom FROM mac_group WHERE group_name = '浏览器组'")
        step('新增会员组：全选后的分类串与权限 JSON', len(g) == 1 and g[0][0] == '1' and g[0][1] == ',1,2,5,'
             and g[0][2] == '{"1":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"2":{"1":"1","2":"2","3":"3","4":"4","5":"5"},'
                           '"5":{"1":"1","2":"2"}}', g)
        page.locator('tr:has-text("VIP会员")').locator('a.j-iframe', has_text='编辑').click()
        f = frame('/admin/group/info')
        f.wait_for_selector('input[name=group_points_day]', timeout=8000)
        checked = f.locator('input[name="group_popedom[1][5]"]').is_checked()
        step('编辑会员组：权限按库里的 JSON 勾选', checked)
        f.fill('input[name=group_points_day]', '15')
        submit_child(f)
        step('编辑会员组：价格写库', sql('SELECT group_points_day FROM mac_group WHERE group_id = 3') == [['15']])
        page.locator('tr:has-text("空组") .layui-form-switch').click()
        page.wait_for_timeout(1500)
        step('会员组状态开关', sql('SELECT group_status FROM mac_group WHERE group_id = 4') == [['0']])
        page.locator('tr:has-text("VIP会员") a.j-tr-del').click()
        page.locator('.layui-layer-dialog .layui-layer-btn0').first.click()
        page.wait_for_selector('.layui-layer-msg:has-text("用户组下还有用户")', timeout=8000)
        step('删除有会员的组：提示且不删', sql('SELECT COUNT(*) FROM mac_group WHERE group_id = 3') == [['1']])

        # ---------------- 充值卡：生成、导出、清空
        open_list('/admin/card/index.html')
        page.locator('a.j-iframe', has_text='添加').first.click()
        f = frame('/admin/card/info')
        f.wait_for_selector('input[name=num]', timeout=8000)
        f.fill('input[name=num]', '5')
        f.fill('input[name=money]', '30')
        f.fill('input[name=point]', '300')
        f.locator('input[name=role_no][value=num] + .layui-form-radio').click()
        submit_child(f)
        cards = sql('SELECT card_no, card_pwd, card_money, card_points FROM mac_card WHERE card_id > 2')
        step('生成 5 张卡（16 位数字卡号、8 位卡密）', len(cards) == 5 and all(re.fullmatch(r'[1-9][0-9]{15}', c[0])
             and len(c[1]) == 8 and c[2] == '30' and c[3] == '300' for c in cards), cards[:2])
        with page.expect_download(timeout=10000) as dl:
            page.click('#btnExport')
        path = dl.value.path()
        csv = open(path, encoding='utf-8').read()
        lines = csv.strip('\n').split('\n')
        step('导出 CSV：文件名、表头与行数', re.fullmatch(r'card_\d{4}-\d{2}-\d{2}\.csv', dl.value.suggested_filename) is not None
             and lines[0] == '卡号,密码,创建时间' and len(lines) == 8, [dl.value.suggested_filename, lines[0], len(lines)])
        shot('card-list')
        page.locator('a.j-ajax', has_text='清空').click()
        confirm_then_reload()
        step('清空充值卡', sql('SELECT COUNT(*) FROM mac_card') == [['0']])

        # ---------------- 订单
        open_list('/admin/order/index.html')
        step('订单列表（2 单）', page.locator('table.layui-table tbody tr').count() == 2)
        page.locator('tr:has-text("PAY20260101120000123456") a.j-tr-del').click()
        confirm_layer()
        step('行内删除订单', sql('SELECT COUNT(*) FROM mac_order WHERE order_id = 1') == [['0']])

        # ---------------- 提现：审核、删除未审核
        open_list('/admin/cash/index.html')
        step('提现列表（3 条）', page.locator('table.layui-table tbody tr').count() == 3)
        page.locator('tr:has-text("银行A") a.j-ajax', has_text='审核').click()
        confirm_then_reload()
        c = sql('SELECT cash_status FROM mac_cash WHERE cash_id = 1') + sql('SELECT user_points, user_points_froze FROM mac_user WHERE user_id = 2')
        step('审核提现：状态已审核、冻结积分扣 20', c == [['1'], ['50', '10']], c)
        page.locator('tr:has-text("银行C") .layui-form-checkbox').click()
        page.locator('a.j-page-btns', has_text='删除').click()
        confirm_then_reload()
        c = sql('SELECT COUNT(*) FROM mac_cash WHERE cash_id = 3') + sql('SELECT user_points, user_points_froze FROM mac_user WHERE user_id = 2')
        step('勾选删除未审核提现：冻结积分退回', c == [['0'], ['60', '0']], c)
        recon = sql('SELECT COUNT(*) FROM (SELECT u.user_id FROM mac_user u LEFT JOIN mac_cash c ON c.user_id = u.user_id '
                    'AND c.cash_status = 0 GROUP BY u.user_id, u.user_points_froze '
                    'HAVING u.user_points_froze <> COALESCE(SUM(c.cash_points), 0)) x')
        step('对账：冻结积分 = 未审核提现合计', recon == [['0']], recon)
        shot('cash-list')

        # ---------------- 积分日志、访问日志、推广访问
        open_list('/admin/plog/index.html')
        step('积分日志列表', page.locator('table.layui-table tbody tr').count() == 2)
        open_list('/admin/ulog/index.html')
        step('访问日志列表（所属内容名称）', '[星际穿越]' in page.inner_text('table.layui-table tbody'))
        page.locator('table.layui-table tbody tr').first.locator('a.j-tr-del').click()
        confirm_layer()
        step('行内删除访问日志', sql('SELECT COUNT(*) FROM mac_ulog') == [['1']])
        open_list('/admin/visit/index.html')
        step('推广访问列表', page.locator('table.layui-table tbody tr').count() == 2)
        page.locator('a.j-ajax', has_text='清空').click()
        confirm_then_reload()
        step('清空推广访问', sql('SELECT COUNT(*) FROM mac_visit') == [['0']])

        report['csp'] = page.evaluate('window.__csp || []')
        browser.close()

    step('无内容安全策略违规', not report['csp'], '; '.join(report['csp'][:5]))
    step('无页面脚本错误', not report['pageErrors'], '; '.join(report['pageErrors'][:5]))
    step('无控制台错误', not report['consoleErrors'], '; '.join(report['consoleErrors'][:5]))
    step('无外部主机请求', not report['external'], '; '.join(report['external'][:5]))
    step('无 4xx/5xx 响应', not report['responses4xx5xx'], '; '.join(report['responses4xx5xx'][:5]))
    with open(os.path.join(out, 'report.json'), 'w', encoding='utf-8') as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    sys.exit(0 if all(s['ok'] for s in report['steps']) else 1)


if __name__ == '__main__':
    main()
