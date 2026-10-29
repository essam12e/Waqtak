import sys, re, os
sys.path.insert(0, os.path.dirname(__file__))
from strings_data import S
res = sys.argv[1]
keys=[k for k,_,_ in S]
dups={k for k in keys if keys.count(k)>1}
assert not dups, dups
def esc(t):
    t=t.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
    t=t.replace('\\','\\\\').replace("'", "\\'").replace('"','\\"')
    if t.startswith('@') or t.startswith('?'): t='\\'+t
    return t
for idx, folder in [(1,'values'),(2,'values-ar')]:
    out=['<?xml version="1.0" encoding="utf-8"?>','<resources xmlns:tools="http://schemas.android.com/tools">']
    for row in S:
        k=row[0]; v=row[idx]
        n_en=len(re.findall(r'%\d\$', row[1])); n_ar=len(re.findall(r'%\d\$', row[2]))
        assert n_en==n_ar, (k, row)
        attrs=''
        if k in ('app_name','lang_ar','lang_en') : attrs=' translatable="false"' if idx==1 and k!='app_name' else ''
        if idx==2 and k in ('lang_ar','lang_en'): continue
        if '%%' in v or (n_en==0 and '%' in v): attrs+=' formatted="false"' if n_en==0 else ''
        ev=esc(v)
        if v!=v.strip(): ev='"'+ev+'"'
        out.append(f'    <string name="{k}"{attrs}>{ev}</string>')
    out.append('</resources>')
    open(f'{res}/{folder}/strings.xml','w').write('\n'.join(out)+'\n')
used=set(open('/dev/stdin').read().split())
missing=used-set(keys); unused=set(keys)-used
print("missing:", missing); print("unused:", unused)
