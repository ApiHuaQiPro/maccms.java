import re,glob,json
names={}
files=glob.glob('php/application/index/controller/*.php')+['php/application/common/controller/All.php']
for f in files:
    src=open(f,encoding='utf-8',errors='ignore').read()
    for i,line in enumerate(src.split('\n'),1):
        for m in re.finditer(r"fetch\(\s*'([\w/]+)'",line):
            names.setdefault(m.group(1),[]).append(f"{f.split('/')[-1]}:{i}")
        for m in re.finditer(r"mac_tpl_fetch\('(\w+)',[^,]+,'(\w+)'\)",line):
            names.setdefault(f"{m.group(1)}/{m.group(2)}",[]).append(f"{f.split('/')[-1]}:{i}(动态默认名)")
names.setdefault('public/jump',[]).append('All.php:112(page_404 默认)')
names.setdefault('public/(page_404 自定义名)',[]).append('All.php:112')
names['label/<文件名>']=['Label.php:22']
json.dump(names,open('.work/tpls.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
print(len(names))
for k in sorted(names): print(k, names[k])
