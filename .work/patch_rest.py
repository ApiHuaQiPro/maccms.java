p = 'backend/src/test/browser/b13_admin_content_check.py'
s = open(p, encoding='utf-8').read()
s = s.replace("page.locator('a.j-page-btns', has_text='保留最小').first.click()",
              "page.locator('a.j-page-btns', has_text='保留小ID').first.click()")
old = """        report['csp'] = page.evaluate('window.__csp || []')"""
new = open('.work/rest_block2.txt', encoding='utf-8').read().rstrip('\n') + '\n\n' + old
assert old in s
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
