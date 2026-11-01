L = open('docs/design/APPK-2/tech-design.md', encoding='utf-8').read().split('\n')

def cols(l):
    n = 0
    i = 0
    while i < len(l):
        if l[i] == '\\':
            i += 2
            continue
        if l[i] == '|':
            n += 1
        i += 1
    return n

hdr = None
for i, l in enumerate(L, 1):
    if l.startswith('|'):
        c = cols(l)
        if hdr is None:
            hdr = c
        elif c != hdr:
            print(i, c, hdr, l[:70])
    else:
        hdr = None
print('checked')
