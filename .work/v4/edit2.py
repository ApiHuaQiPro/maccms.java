import io, sys
p = 'docs/design/APPK-2/tech-design.md'
s = io.open(p, encoding='utf-8').read()
def rep(old, new):
    global s
    if s.count(old) != 1: sys.exit('MATCH %d %s' % (s.count(old), ascii(old[:60])))
    s = s.replace(old, new)
rep('六项安全修复除外，见 §6.14）', '六项安全修复除外，见 §6.17）')
rep('**v3 范围（完整设计）**：FR-1～FR-13 全部；改动清单 C-17～C-66；验证 V-10～V-29；',
    '**v3 范围（完整设计）**：FR-1～FR-13 全部；改动清单 C-17～C-66（v4 增 C-67、C-68）；验证 V-10～V-29（v4 增 V-30）；')
io.open(p, 'w', encoding='utf-8', newline='\r\n').write(s)
print('ok')
