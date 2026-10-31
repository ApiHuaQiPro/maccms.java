import re,json,sys
sql=open('php/application/install/sql/install.sql',encoding='utf-8').read()
tabs={}
for m in re.finditer(r"CREATE TABLE `(\w+)` \((.*?)\n\)\s*(ENGINE=[^;]*);",sql,re.S):
    name,body,opts=m.groups()
    cols=[];keys=[]
    for line in body.split('\n'):
        line=line.strip().rstrip(',')
        if not line: continue
        if line.startswith('`'):
            cm=re.match(r"`(\w+)`\s+(.*)",line)
            cols.append((cm.group(1),cm.group(2)))
        else: keys.append(line)
    tabs[name]=dict(cols=cols,keys=keys,opts=opts.strip())
json.dump(tabs,open('.work/tabs.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
tot=0
for n,t in tabs.items():
    print(n,len(t['cols']),len(t['keys']),t['opts']); tot+=len(t['cols'])
print(tot)
