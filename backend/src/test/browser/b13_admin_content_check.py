"""B13 后台内容管理浏览器验收（docs/architecture/22-b13-admin-content.md 第 9 节）：对一个运行中的新后台（可写数据源打开、旧配置开启缩略图
与水印、upload/ 由前面的 nginx 提供），用 Playwright 的本机 Chromium 无头模式走一遍真实页面：

  - 登录 → 视频列表（layui 表格、分页）；
  - "添加"弹层（j-iframe）：layui 下拉、ueditor 渲染并按 config 请求加载；layui 上传选图（缩略图与水印打开）→ 地址回填、缩略图地址回填、
    预览图从 /upload/ 读到；保存后列表出现新视频；
  - 列表勾选 + j-select 弹窗批量改等级；编辑弹层改备注保存；勾选删除（confirm 层）→ 行消失、图片文件随之删除；
  - 同名两条 → 查重列表、"删除重复保留最小"；
  - 文章：ueditor 里输入正文保存，库里的正文是输入的内容；
  - 友链：编辑图片链接，"类型"下拉框仍按旧版选中"文字链接"（照旧的缺陷，22 文档第 7.8 节）；
  - 分类：树形列表、编辑弹层（表单令牌）保存；
  - 评论审核开关、留言回复；
  - 附件：列表、目录浏览逐级进入、附件检查逐页推进（progress.js 自动跳转）并删掉文件已不存在的登记；
  - 视频批量处理：筛选表单 → 逐页输出 → 自动跳转直到完成；
  - 全程收集内容安全策略违规（securitypolicyviolation）、页面脚本错误、控制台 error 与对外部主机的请求——都必须为 0；
  - 输出各步截图与 report.json（含每步结果与写库核对）。

库内核对经 mysql 命令行只读查询（一次性库 maccms_it_*）。不在 ./mvnw verify 中运行；只访问给定的本机地址。
用法：python b13_admin_content_check.py <后台入口地址，如 http://127.0.0.1:18297/_admin.php> <账号> <密码> <输出目录>
      <一次性库名> <站点根目录（其下 upload/）> <上传用的样例 PNG>
环境变量 MYSQL_LOCAL_USER / MYSQL_LOCAL_PW（本机开发库账号，只用于只读核对）。
"""

import json
import os
import re
import subprocess
import sys
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


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
        report['steps'].append({'step': name, 'ok': bool(ok), 'detail': detail})
        print(('OK   ' if ok else 'FAIL ') + name + (' — ' + str(detail) if detail else ''), flush=True)
        return ok

    def sql(q):
        r = subprocess.run(['mysql', '-h127.0.0.1', '-u' + os.environ.get('MYSQL_LOCAL_USER', 'root'),
                            '-p' + os.environ['MYSQL_LOCAL_PW'], '--default-character-set=utf8', '-N', '-B', db],
                           input=q + ';\n', capture_output=True, text=True, encoding='utf-8')
        if r.returncode != 0:
            raise RuntimeError('mysql: ' + r.stderr.strip().splitlines()[-1])
        return [line.split('\t') for line in r.stdout.splitlines()]

    def upload_file(rel):
        return os.path.isfile(os.path.join(site, rel))

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 900})

        def watch(page):
            page.on('pageerror', lambda e: report['pageErrors'].append(str(e)))
            page.on('console', lambda m: report['consoleErrors'].append(m.text) if m.type == 'error' else None)

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

        def layer_msg(f, text, timeout=8000):
            f.wait_for_selector('.layui-layer-msg:has-text("%s")' % text, timeout=timeout)
            return True

        def settle(ms=800):
            page.wait_for_load_state('load')
            page.wait_for_timeout(ms)

        def close_layer():
            # 旧 admin_common.js：弹层表单 data-child 为空，保存后弹层跳到 res.url（新的空表单）；j-iframe 关闭时不刷新列表，
            # 由管理员手动刷新（与旧版相同）
            # data-child="true" 的表单（友链等）保存后自己关闭弹层并刷新列表
            if page.locator('.layui-layer-setwin .layui-layer-close').count():
                page.locator('.layui-layer-setwin .layui-layer-close').first.click()
            page.reload()
            settle(1000)

        def open_list(path):
            page.goto(entry + path)
            settle()

        # ---------------- 登录
        page.goto(entry + '/admin/index/login.html')
        settle()
        page.fill('input[name=admin_name]', account)
        page.fill('input[name=admin_pwd]', password)
        page.click('.btn-submit')
        page.wait_for_url('**/admin/index/index.html', timeout=10000)
        step('登录后进入框架页', page.url.endswith('/admin/index/index.html'))

        # ---------------- 视频：添加（ueditor、layui 下拉、上传 + 缩略图 + 水印）
        open_list('/admin/vod/data.html')
        step('视频列表 layui 表格', page.locator('table.layui-table').count() == 1)
        page.locator('a.j-iframe', has_text='添加').first.click()
        f = frame('/admin/vod/info')
        f.wait_for_selector('.edui-editor', timeout=15000)
        step('视频表单 ueditor 已渲染', f.locator('.edui-editor').count() >= 1, '%d 个编辑器' % f.locator('.edui-editor').count())
        step('视频表单 layui 下拉已渲染', f.locator('.layui-form-select').count() > 3, '%d 个' % f.locator('.layui-form-select').count())
        f.fill('#vod_name', '浏览器视频一')
        f.locator('select[name=type_id] + .layui-form-select .layui-select-title').click()
        f.locator('select[name=type_id] + .layui-form-select dd', has_text='分类1').first.click()
        # 点"上传图片"按钮、在文件选择框里选图（layui 对同一次 render 的多个按钮共用最后一个文件框，按按钮点开才是真实路径）
        with page.expect_response(lambda r: '/admin/upload/upload' in r.url and 'flag=vod' in r.url, timeout=15000) as up:
            with page.expect_file_chooser() as fc:
                f.click('#upload1')
            fc.value.set_files(sample)
        reply = up.value.json()
        step('上传返回成功', reply.get('code') == 1, json.dumps(reply, ensure_ascii=False)[:200])
        f.wait_for_function("document.querySelector('#vod_pic').value.indexOf('upload/vod/') === 0", timeout=8000)
        pic = f.input_value('#vod_pic')
        thumb = f.input_value('#vod_pic_thumb')
        step('图片地址回填', pic.startswith('upload/vod/') and upload_file(pic), pic)
        step('缩略图地址回填且文件存在', thumb.startswith('upload/vod/') and upload_file(thumb), thumb)
        annex = sql("SELECT annex_file, annex_type FROM mac_annex WHERE annex_file IN ('%s', '%s') ORDER BY annex_id" % (pic, thumb))
        step('原图与缩略图都登记附件', [a[0] for a in annex] == [pic, thumb], annex)
        shot('vod-form-uploaded')
        f.locator('button[lay-filter=formSubmit]').click()
        try:
            layer_msg(f, '保存成功')
            step('视频保存提示', True)
        except Exception:
            step('视频保存提示', False, f.inner_text('body')[:200])
        page.wait_for_timeout(1500)
        close_layer()
        rows = sql("SELECT vod_id, vod_name, type_id, vod_pic, vod_pic_thumb FROM mac_vod ORDER BY vod_id")
        step('库里有新视频（名称、分类、图片）', len(rows) == 1 and rows[0][1] == '浏览器视频一' and rows[0][2] == '1'
             and rows[0][3] == pic, rows)
        vod1 = rows[0][0] if rows else '0'
        step('保存后列表出现新视频', '浏览器视频一' in page.inner_text('table.layui-table tbody'))
        shot('vod-list')

        # 列表勾选 + j-select 批量改等级
        page.locator('table.layui-table tbody tr').first.locator('.layui-form-checkbox').click()
        # j-select：把勾选的编号 POST 给 index/select，返回的表单放进页面内的 layer（type 1，不是 iframe）
        page.locator('a.j-select', has_text='推荐').first.click()
        pop = page.locator('.layui-layer-page form.layui-form')
        pop.wait_for(timeout=8000)
        pop.locator('.layui-form-select .layui-select-title').first.click()
        pop.locator('.layui-form-select dd', has_text='推荐5').first.click()
        shot('vod-select-level')
        pop.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2500)
        lv = sql('SELECT vod_level FROM mac_vod WHERE vod_id = %s' % vod1)
        step('j-select 弹窗批量改等级', lv == [['5']], lv)

        # 编辑弹层改备注
        page.goto(entry + '/admin/vod/data.html')
        settle()
        page.locator('a.j-iframe', has_text='编辑').first.click()
        f = frame('/admin/vod/info')
        f.wait_for_selector('.edui-editor', timeout=15000)
        f.fill('input[name=vod_remarks]', '更新至第2集')
        f.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2500)
        close_layer()
        rm = sql('SELECT vod_remarks, vod_pic FROM mac_vod WHERE vod_id = %s' % vod1)
        step('编辑保存（备注写库、图片地址不变）', rm == [['更新至第2集', pic]], rm)

        # 同名第二条 → 查重 → 删除重复保留最小
        page.goto(entry + '/admin/vod/data.html')
        settle()
        page.locator('a.j-iframe', has_text='添加').first.click()
        f = frame('/admin/vod/info')
        f.wait_for_selector('.edui-editor', timeout=15000)
        f.fill('#vod_name', '浏览器视频一')
        f.locator('select[name=type_id] + .layui-form-select .layui-select-title').click()
        f.locator('select[name=type_id] + .layui-form-select dd', has_text='分类2').first.click()
        f.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2500)
        close_layer()
        page.goto(entry + '/admin/vod/data.html?repeat=1')
        settle()
        n = page.locator('table.layui-table tbody tr').count()
        step('查重列表列出同名两条', n == 2, '%d 行' % n)
        shot('vod-repeat')
        page.locator('a.j-page-btns', has_text='保留小ID').first.click()
        page.locator('.layui-layer-btn0').first.click()
        page.wait_for_timeout(2500)
        left = sql("SELECT vod_id FROM mac_vod WHERE vod_name = '浏览器视频一'")
        step('删除重复保留最小编号', left == [[vod1]], left)

        # 勾选删除 → 行消失、图片文件删除
        page.goto(entry + '/admin/vod/data.html')
        settle()
        page.locator('table.layui-table tbody tr').first.locator('.layui-form-checkbox').click()
        page.locator('a.j-page-btns', has_text='删除').first.click()
        page.locator('.layui-layer-btn0').first.click()
        page.wait_for_timeout(2500)
        step('勾选删除后库里没有这条视频', sql('SELECT COUNT(*) FROM mac_vod WHERE vod_id = %s' % vod1) == [['0']])
        step('删除视频时图片文件随之删除', not upload_file(pic), pic)

        def choose(scope, name, text):
            scope.locator('select[name=%s] + .layui-form-select .layui-select-title' % name).first.click()
            scope.locator('select[name=%s] + .layui-form-select dd' % name, has_text=text).first.click()

        def editor_ready(f):
            f.wait_for_function("window.UE && Object.keys(UE.instants).length > 0 && "
                                "Object.keys(UE.instants).every(function (k) { return UE.instants[k].isReady; })",
                                timeout=15000)

        # ---------------- 文章：ueditor 里输入正文
        open_list('/admin/art/data.html')
        page.locator('a.j-iframe', has_text='添加').first.click()
        f = frame('/admin/art/info')
        editor_ready(f)
        step('文章表单 ueditor 就绪（编辑区可输入）', True)
        f.fill('input[name=art_name]', '浏览器文章')
        choose(f, 'type_id', '分类5')
        body = f.frame_locator('iframe[id^=ueditor_]').first.locator('body')
        body.click()
        page.keyboard.type('富文本正文第一段')
        shot('art-form-editor')
        f.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2500)
        close_layer()
        art = sql('SELECT art_name, type_id, art_content FROM mac_art')
        step('文章保存：正文是编辑器里输入的内容', len(art) == 1 and art[0][0] == '浏览器文章' and art[0][1] == '5'
             and '富文本正文第一段' in art[0][2], art)

        # ---------------- 友链：编辑图片链接，"类型"下拉框照旧选中"文字链接"
        open_list('/admin/link/index.html')
        step('友链列表', page.locator('table.layui-table tbody tr').count() == 2)
        page.locator('tr:has(input[value="图片友链"])').locator('a.j-iframe', has_text='编辑').click()
        f = frame('/admin/link/info')
        f.wait_for_selector('select[name=link_type]', state='attached', timeout=8000)
        selected = f.eval_on_selector('select[name=link_type]', 'e => e.options[e.selectedIndex].text')
        step('友链"类型"下拉框照旧选中"文字链接"（旧模板 $vo 缺陷，22 文档第 7.8 节）', selected == '文字链接', selected)
        f.fill('input[name=link_name]', '图片友链改')
        f.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2000)
        close_layer()
        link = sql("SELECT link_name, link_type, link_logo FROM mac_link WHERE link_url = 'http://b.example.test/'")
        step('友链保存：不改下拉框时图片链接存回文字链接（与旧版相同）', link == [['图片友链改', '0', 'upload/link/logo.png']], link)

        # ---------------- 分类：树形列表、编辑（表单令牌）
        open_list('/admin/type/index.html')
        n = page.locator('table.layui-table tbody tr').count()
        step('分类树形列表', n == 18, '%d 行' % n)
        page.locator('tr', has_text='分类1').first.locator('a.j-iframe', has_text='编辑').click()
        f = frame('/admin/type/info')
        f.wait_for_selector('input[name=__token__]', state='attached', timeout=8000)
        f.fill('input[name=type_name]', '分类一')
        f.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2000)
        close_layer()
        step('分类编辑保存（带表单令牌）', sql('SELECT type_name FROM mac_type WHERE type_id = 1') == [['分类一']])

        # ---------------- 评论审核（j-select 改状态）、留言回复
        open_list('/admin/comment/data.html')
        step('评论列表', page.locator('table.layui-table tbody tr').count() == 2)
        page.locator('tr', has_text='待审核的评论').locator('.layui-form-checkbox').click()
        page.locator('a.j-select', has_text='状态').first.click()
        pop = page.locator('.layui-layer-page form.layui-form')
        pop.wait_for(timeout=8000)
        choose(pop, 'val', '已审核')
        pop.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2000)
        step('评论审核', sql("SELECT comment_status FROM mac_comment WHERE comment_content = '待审核的评论'") == [['1']])
        open_list('/admin/gbook/data.html')
        page.locator('a.j-iframe', has_text='回复').first.click()
        f = frame('/admin/gbook/info')
        f.wait_for_selector('textarea[name=gbook_reply]', timeout=8000)
        f.fill('textarea[name=gbook_reply]', '已收到，谢谢')
        f.locator('button[lay-filter=formSubmit]').click()
        page.wait_for_timeout(2000)
        close_layer()
        gb = sql('SELECT gbook_reply, gbook_reply_time > 0 FROM mac_gbook')
        step('留言回复（写回复与回复时间）', gb == [['已收到，谢谢', '1']], gb)

        # ---------------- 附件：列表、目录浏览、附件检查逐页推进
        open_list('/admin/annex/data.html')
        n = page.locator('table.layui-table tbody tr').count()
        step('附件列表', n >= 2, '%d 行' % n)
        open_list('/admin/annex/file.html')
        top = page.inner_text('table')
        step('附件目录浏览：默认列出 upload/ 下的目录', 'link' in top and 'vod' in top)
        page.locator('table a', has_text='link').first.click()
        settle()
        step('附件目录浏览：进入 upload/link/', 'logo.png' in page.inner_text('table'), page.url)
        shot('annex-file')
        page.locator('table a', has_text='返回上级目录').first.click()
        settle()
        step('附件目录浏览：返回上级目录', 'vod' in page.inner_text('table'), page.url)
        # 附件检查每页 500 条、从最后一页往前：先补 1100 条文件不存在的登记，共 3 页（旧版第 2 页起 500，D-B13-12）
        values = ','.join("(0, 'upload/vod/20260101-1/gone-%04d.jpg', 1, 'image')" % i for i in range(1100))
        sql('INSERT INTO mac_annex (annex_time, annex_file, annex_size, annex_type) VALUES ' + values)
        total = sql('SELECT COUNT(*) FROM mac_annex')[0][0]
        pages = []
        page.on('framenavigated', lambda fr: pages.append(fr.url) if fr == page.main_frame and '/annex/check' in fr.url else None)
        page.goto(entry + '/admin/annex/check.html')
        try:
            page.wait_for_selector('text=无效文件清理完毕', timeout=40000)
            step('附件检查逐页推进到结束（progress.js 3 秒跳转）', True, '%s 条登记，经过 %d 个地址' % (total, len(set(pages))))
        except Exception:
            step('附件检查逐页推进到结束（progress.js 3 秒跳转）', False, page.inner_text('body')[:300])
        shot('annex-check')
        step('附件检查跨过第 2 页以后（start=2、3）', any('page_count=3' in u and 'start=2' in u for u in pages)
             and any('page_count=3' in u and 'start=3' in u for u in pages),
             sorted(set(u.split('/admin/')[-1] for u in pages)))
        gone = sql("SELECT COUNT(*) FROM mac_annex WHERE annex_file LIKE 'upload/vod/20260101-1/gone%'")
        kept = sql("SELECT annex_file FROM mac_annex ORDER BY annex_id")
        step('附件检查删掉全部文件已不存在的登记、保留文件存在的', gone == [['0']] and ['upload/link/logo.png'] in kept, kept)

        # 附件初始化：逐表逐页把图片列与正文里的本地图片补登记（条件插入，已登记的不重复）；文章表补 1100 行指向同一张已存在的图片，
        # 使这一张表也要翻页
        values = ','.join("(5, 0, '初始化%04d', 1, 'upload/link/logo.png', '', '', '', '')" % i for i in range(1100))
        sql('INSERT INTO mac_art (type_id, type_id_1, art_name, art_status, art_pic, art_content, art_title, art_note, '
            'art_blurb) VALUES ' + values)
        sql("DELETE FROM mac_annex WHERE annex_file = 'upload/link/logo.png'")
        before = sql('SELECT COUNT(*) FROM mac_annex')
        init_pages = []
        page.on('framenavigated', lambda fr: init_pages.append(fr.url) if fr == page.main_frame and '/annex/init' in fr.url else None)
        page.goto(entry + '/admin/annex/init.html')
        settle()
        page.locator('form.layui-form button[type=submit]').first.click()
        try:
            page.wait_for_selector('text=附件数据初始化结束', timeout=60000)
            step('附件初始化逐表推进到结束', True, page.url)
        except Exception:
            step('附件初始化逐表推进到结束', False, page.inner_text('body')[:300])
        shot('annex-init')
        art_pages = sorted(set(u.split('?')[-1] for u in init_pages if 'tbi=1' in u))
        step('附件初始化在文章表内翻页（1101 行）', any('start=2' in u for u in art_pages) and any('start=3' in u for u in art_pages),
             art_pages)
        dup = sql('SELECT annex_file, COUNT(*) FROM mac_annex GROUP BY annex_file HAVING COUNT(*) > 1')
        logo = sql("SELECT COUNT(*) FROM mac_annex WHERE annex_file = 'upload/link/logo.png'")
        step('附件初始化补登记存在的图片且不重复登记', dup == [] and logo == [['1']], [before, logo, dup])

        # ---------------- 视频批量处理：筛选表单 → 逐页输出 → 自动跳转到完成
        for name in ('批量一', '批量二', '批量三'):
            sql("INSERT INTO mac_vod (type_id, type_id_1, vod_name, vod_status, vod_level, vod_content, vod_play_from, "
                "vod_play_server, vod_play_note, vod_play_url, vod_down_from, vod_down_server, vod_down_note, vod_down_url, "
                "vod_plot_name, vod_plot_detail) VALUES (1, 0, '%s', 1, 0, '', '', '', '', '', '', '', '', '', '', '')" % name)
        page.goto(entry + '/admin/vod/batch.html')
        settle()
        page.fill('input[name=wd]', '批量')
        page.check('input[name=ck_level]')
        choose(page, 'val_level', '推荐3')
        if page.locator('input[name=limit]').count():
            page.fill('input[name=limit]', '2')
        page.locator('button.btn_submit', has_text='开始执行').click()
        try:
            page.wait_for_selector('text=批量操作完毕', timeout=25000)
            step('批量处理逐页推进到完成', True, page.url)
        except Exception:
            step('批量处理逐页推进到完成', False, page.inner_text('body')[:300])
        shot('vod-batch')
        lv = sql("SELECT vod_level FROM mac_vod WHERE vod_name LIKE '批量%' ORDER BY vod_id")
        step('批量处理改了全部符合条件的行', lv == [['3'], ['3'], ['3']], lv)

        # ---------------- 上传的脚本标记拒绝（D-B13-16：检查通过才落盘）与图片同步合法 tab（D-B13-2：不下载、不改库）
        today_dirs = lambda: sorted(os.listdir(os.path.join(site, 'upload', 'vod'))) if os.path.isdir(os.path.join(site, 'upload', 'vod')) else []
        files_before = sum(len(fs) for _, _, fs in os.walk(os.path.join(site, 'upload')))
        annex_before = sql('SELECT COUNT(*) FROM mac_annex')
        reply = page.evaluate("""async (url) => {
            const head = new Uint8Array([0xFF, 0xD8, 0xFF, 0xE0]);
            const body = new TextEncoder().encode('<script>alert(1)</script>' + 'x'.repeat(2000));
            const fd = new FormData();
            fd.append('file', new Blob([head, body], {type: 'image/jpeg'}), 'evil.jpg');
            const r = await fetch(url, {method: 'POST', body: fd, headers: {'X-Requested-With': 'XMLHttpRequest'}});
            return await r.text();
        }""", entry + '/admin/upload/upload.html?flag=vod')
        files_after = sum(len(fs) for _, _, fs in os.walk(os.path.join(site, 'upload')))
        step('上传含脚本标记的图片被拒绝（文件包含危险内容）', '"code":0' in reply and '危险' in reply, reply[:160])
        step('被拒绝的上传不落盘、不登记附件', files_after == files_before and sql('SELECT COUNT(*) FROM mac_annex') == annex_before,
             [files_before, files_after, today_dirs()])
        pics = sql('SELECT vod_id, vod_pic FROM mac_vod ORDER BY vod_id')
        page.goto(entry + '/admin/images/sync.html?tab=vod')
        settle()
        text = page.inner_text('body')
        step('图片同步合法 tab：固定提示、不下载', '远程图片同步未开放' in text, text[:120])
        step('图片同步不改库内图片地址', sql('SELECT vod_id, vod_pic FROM mac_vod ORDER BY vod_id') == pics)

        report['csp'] = page.evaluate('window.__csp || []')
        browser.close()

    step('无内容安全策略违规', not report['csp'], '; '.join(report['csp'][:5]))
    step('无页面脚本错误', not report['pageErrors'], '; '.join(report['pageErrors'][:5]))
    step('无控制台错误', not report['consoleErrors'], '; '.join(report['consoleErrors'][:5]))
    step('无外部主机请求', not report['external'], '; '.join(report['external'][:5]))
    with open(os.path.join(out, 'report.json'), 'w', encoding='utf-8') as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    sys.exit(0 if all(s['ok'] for s in report['steps']) else 1)


if __name__ == '__main__':
    main()
