from playwright.sync_api import sync_playwright
entry='http://127.0.0.1:18297/_admin.php'
with sync_playwright() as p:
    b=p.chromium.launch(headless=True); page=b.new_page()
    page.on('console', lambda m: print('CONSOLE', m.type, m.text))
    page.on('pageerror', lambda e: print('PAGEERR', e))
    page.on('request', lambda r: print('REQ', r.method, r.url) if 'upload' in r.url else None)
    page.on('response', lambda r: print('RESP', r.status, r.url) if 'upload' in r.url or r.status>=400 else None)
    page.goto(entry+'/admin/index/login.html'); page.fill('input[name=admin_name]','admin'); page.fill('input[name=admin_pwd]','b13-browser-pass'); page.click('.btn-submit'); page.wait_for_url('**/index.html')
    page.goto(entry+'/admin/vod/info.html'); page.wait_for_load_state('networkidle')
    inp=page.locator('#upload1 + input.layui-upload-file')
    print('count', inp.count(), inp.evaluate('e=>e.outerHTML'))
    inp.set_input_files('.work/b13br/site/upload-sample.png')
    page.wait_for_timeout(4000)
    print('pic', page.input_value('#vod_pic'), 'thumb', page.input_value('#vod_pic_thumb'))
    b.close()
