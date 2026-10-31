import re,glob,os,json
res={}
for grp in ['index','api','admin']:
    for f in sorted(glob.glob(f'php/application/{grp}/controller/*.php')):
        src=open(f,encoding='utf-8',errors='ignore').read()
        ms=re.findall(r"^\s*public\s+function\s+(\w+)\s*\(",src,re.M)
        res[f"{grp}/{os.path.basename(f)[:-4]}"]=ms
f='php/application/install/controller/Index.php'
src=open(f,encoding='utf-8',errors='ignore').read()
res['install/Index']=re.findall(r"^\s*public\s+function\s+(\w+)\s*\(",src,re.M)
json.dump(res,open('.work/methods.json','w'),indent=1)
for g in ['index','api','admin','install']:
    ks=[k for k in res if k.startswith(g+'/')]
    print(g,len(ks),sum(len(res[k]) for k in ks))
for k,v in res.items(): print(k,len(v),' '.join(v)[:200])
