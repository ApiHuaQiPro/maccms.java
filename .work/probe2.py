import sys
from playwright.sync_api import sync_playwright
entry='http://127.0.0.1:18297/_admin.php'
with sync_playwright() as p:
    b=p.chromium.launch(headless=True); ctx=b.new_context()
    ctx.add_init_script("document.addEventListener('securitypolicyviolation', function(e){ (window.top.__csp=window.top.__csp||[]).push([e.violatedDirective,e.blockedURI,e.sourceFile,e.lineNumber,e.columnNumber,e.sample].join(' | ')); });")
    page=ctx.new_page()
    page.goto(entry+'/admin/index/login.html'); page.fill('input[name=admin_name]','admin'); page.fill('input[name=admin_pwd]','b13-browser-pass'); page.click('.btn-submit'); page.wait_for_url('**/index.html')
    for path in sys.argv[1:]:
        page.goto(entry+path); page.wait_for_load_state('networkidle'); page.wait_for_timeout(500)
        print(path, page.evaluate('window.__csp||[]'))
    b.close()
