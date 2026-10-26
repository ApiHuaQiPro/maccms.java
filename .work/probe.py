import sys
from playwright.sync_api import sync_playwright
entry='http://127.0.0.1:18297/_admin.php'
with sync_playwright() as p:
    b=p.chromium.launch(headless=True); page=b.new_page()
    page.on('console', lambda m: print('CONSOLE', m.type, m.text))
    page.on('response', lambda r: print('RESP', r.status, r.url) if r.status>=400 else None)
    page.on('pageerror', lambda e: print('PAGEERR', e))
    page.goto(entry+'/admin/index/login.html'); page.fill('input[name=admin_name]','admin'); page.fill('input[name=admin_pwd]','b13-browser-pass'); page.click('.btn-submit'); page.wait_for_url('**/index.html')
    page.goto(entry+sys.argv[1]); page.wait_for_load_state('networkidle')
    print(page.evaluate(sys.argv[2]))
    b.close()
