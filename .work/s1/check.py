import re
t = open('docs/design/APPK-2/tech-design.md', encoding='utf-8').read()
words = ['相关文件', '适当', '尽量', '视情况', '优化一下', 'D:' + chr(92), 'C:' + chr(92), 'E:' + chr(92), '等文件']
for w in words:
    for m in re.finditer(re.escape(w), t):
        ln = t.count('\n', 0, m.start()) + 1
        print('HIT', w, ln, t[max(0, m.start() - 30):m.start() + 30].replace('\n', ' '))
for pfx in ['C-', 'V-', 'DEC-', 'RISK-T', 'OP-T', 'ENV-', 'PATH-', 'TPL-', 'RT-', 'PG-', 'ALIGN-', 'DB-', 'INS-', 'NET-', 'SEC-', 'CACHE-', 'SNAPD-']:
    refs = sorted(set(int(x) for x in re.findall(r'(?<![A-Za-z])' + re.escape(pfx) + r'(\d+)', t)))
    print(pfx, refs)
