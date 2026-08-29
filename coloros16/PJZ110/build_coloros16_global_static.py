#!/usr/bin/env python3
from __future__ import annotations
import argparse, copy, json, shutil
from pathlib import Path
from lxml import etree

WEIGHTS=[100,200,300,400,500,600,700,800,900,1000]
LATIN_NORMAL={100:'SourceSans3-ExtraLight.ttf',200:'SourceSans3-ExtraLight.ttf',300:'SourceSans3-Light.ttf',400:'SourceSans3-Regular.ttf',500:'SourceSans3-Medium.ttf',600:'SourceSans3-Semibold.ttf',700:'SourceSans3-Bold.ttf',800:'SourceSans3-Bold.ttf',900:'SourceSans3-Black.ttf',1000:'SourceSans3-Black.ttf'}
LATIN_ITALIC={100:'SourceSans3-ExtraLightIt.ttf',200:'SourceSans3-ExtraLightIt.ttf',300:'SourceSans3-LightIt.ttf',400:'SourceSans3-It.ttf',500:'SourceSans3-MediumIt.ttf',600:'SourceSans3-SemiboldIt.ttf',700:'SourceSans3-BoldIt.ttf',800:'SourceSans3-BoldIt.ttf',900:'SourceSans3-BlackIt.ttf',1000:'SourceSans3-BlackIt.ttf'}
CJK={100:'200.ttf',200:'200.ttf',300:'300.ttf',400:'400.ttf',500:'400.ttf',600:'700.ttf',700:'700.ttf',800:'700.ttf',900:'700.ttf',1000:'700.ttf'}
MONO_N={w:('JetBrainsMono-Bold.ttf' if w>=600 else 'JetBrainsMono-Regular.ttf') for w in WEIGHTS}
MONO_I={w:('JetBrainsMono-BoldItalic.ttf' if w>=600 else 'JetBrainsMono-Italic.ttf') for w in WEIGHTS}
SUPPLEMENT_FILES={'MFGA.ttf','BraillePatterns.ttf','PlangothicP1.ttf','PlangothicP2.ttf','NotoSansPro.otf','UnicodiaFunky.ttf','ArchaicCuneiformNumerals.ttf','Unicode18-new.ttf','Unicode17-new.ttf','SatisarSharada-Regular.ttf','Unicode16-new.ttf','Unknown-symbol-supplementRegular.ttf','NotoUnicode.otf','Tibetan-Regular.ttf','Private-UseTest.ttf','ZUno-Number.ttf','IBMPlexMath-Regular.otf'}
PARSER=etree.XMLParser(remove_blank_text=False,remove_comments=False)

def font(filename,w,style='normal',fallback=None):
    a={'weight':str(w),'style':style}
    if fallback:a['fallbackFor']=fallback
    e=etree.Element('font',**a); e.text=filename; return e

def static_children(nmap,imap=None,serif_fallback=False):
    out=[]
    for w in WEIGHTS:
        out.append(font(nmap[w],w,'normal'))
        if serif_fallback and w==400: out.append(font(nmap[w],w,'normal','serif'))
        if imap: out.append(font(imap[w],w,'italic'))
    return out

def fam(root,name):
    return next((x for x in root.findall('family') if x.get('name')==name),None)

def replace(root,name,children):
    f=fam(root,name)
    if f is None:return False
    for c in list(f):f.remove(c)
    for c in children:f.append(copy.deepcopy(c))
    return True

def replace_lang(root,langs,oldrefs):
    n=0
    for f in root.findall('family'):
        lang=f.get('lang','')
        if not any(x in lang for x in langs):continue
        refs=[(x.text or '').strip() for x in f.findall('font')]
        if not any(any(o in r for o in oldrefs) for r in refs):continue
        for c in list(f):f.remove(c)
        for c in static_children(CJK,None,True):f.append(copy.deepcopy(c))
        n+=1
    return n

def remove_named(root,name):
    for x in list(root):
        if x.tag in ('family','alias') and x.get('name')==name:root.remove(x)

def add_alias(root,name,to,weight=None):
    remove_named(root,name)
    a={'name':name,'to':to}
    if weight is not None:a['weight']=str(weight)
    root.append(etree.Element('alias',**a))

def refs(root):
    return {(x.text or '').strip() for x in root.findall('.//font') if (x.text or '').strip()}

def append_supplements(root,r1,module_fonts):
    existing=refs(root); added=[]
    for f in r1.findall('family'):
        fs=[(x.text or '').strip() for x in f.findall('.//font') if (x.text or '').strip()]
        hits=[x for x in fs if x in SUPPLEMENT_FILES]
        if not hits:continue
        if any(x in SUPPLEMENT_FILES and not (module_fonts/x).exists() for x in fs):continue
        if all(x in existing for x in hits):continue
        root.append(copy.deepcopy(f)); existing.update(fs); added+=hits
    return sorted(set(added))

def patch(src,r1xml,module_fonts,dst):
    tree=etree.parse(str(src),PARSER); root=tree.getroot(); r1=etree.parse(str(r1xml),PARSER).getroot(); c=[]
    if replace(root,'sans-serif',static_children(LATIN_NORMAL,LATIN_ITALIC)):c.append('sans-serif -> Source Sans 3 static weight family')
    if replace(root,'sys-sans-en',static_children(LATIN_NORMAL)):c.append('sys-sans-en -> Source Sans 3 static weight family')
    if replace(root,'op-sans-en',static_children(LATIN_NORMAL)):c.append('op-sans-en -> Source Sans 3 static weight family')
    if replace(root,'sans-serif-condensed',static_children(LATIN_NORMAL,LATIN_ITALIC)):c.append('sans-serif-condensed -> Source Sans 3')
    if replace(root,'serif',static_children(CJK)):c.append('serif -> LXGW main')
    if replace(root,'sys-serif',static_children(CJK)):c.append('sys-serif(OPPO Serif route) -> LXGW main')
    if replace(root,'monospace',static_children(MONO_N,MONO_I)):c.append('monospace -> JetBrains Mono')
    if replace(root,'serif-monospace',static_children(MONO_N,MONO_I)):c.append('serif-monospace -> JetBrains Mono')
    n=replace_lang(root,['zh-Hans'],['SysSans-Hans-Regular.ttf','NotoSansCJK-Regular.ttc'])
    if n:c.append(f'zh-Hans -> LXGW x{n}')
    n=replace_lang(root,['zh-Hant','zh-Bopo'],['SysSans-Hant-Regular.ttf','NotoSansCJK-Regular.ttc'])
    if n:c.append(f'zh-Hant/Bopo -> LXGW x{n}')
    remove_named(root,'zdigit');remove_named(root,'zdigit-for-medium')
    if fam(root,'osans-solid-digits') is not None:
        add_alias(root,'zdigit','osans-solid-digits');add_alias(root,'zdigit-for-medium','osans-solid-digits')
        for name,w in [('zdigit-thin',100),('zdigit-light',300),('zdigit-regular',400),('zdigit-medium',500),('zdigit-bold',700)]:add_alias(root,name,'osans-solid-digits',w)
        c.append('legacy zdigit aliases -> osans-solid-digits')
    a=append_supplements(root,r1,module_fonts)
    if a:c.append('supplements:'+','.join(a))
    rss=fam(r1,'source-sans-pro')
    if rss is not None and fam(root,'source-sans-pro') is not None:
        old=fam(root,'source-sans-pro');idx=list(root).index(old);root.remove(old);root.insert(idx,copy.deepcopy(rss));c.append('source-sans-pro -> R1')
    dst.parent.mkdir(parents=True,exist_ok=True);tree.write(str(dst),encoding='utf-8',xml_declaration=True,pretty_print=True)
    return c

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--module',required=True);ap.add_argument('--templates',required=True);ap.add_argument('--report');a=ap.parse_args()
    m=Path(a.module);t=Path(a.templates);mf=m/'system/fonts';r1=m/'fonts.xml'
    mp={'system__etc__fonts.xml':m/'system/etc/fonts.xml','system__etc__font_fallback.xml':m/'system/etc/font_fallback.xml','system_ext__etc__fonts_base.xml':m/'system_ext/etc/fonts_base.xml','system_ext__etc__fonts_ule.xml':m/'system_ext/etc/fonts_ule.xml'}
    rep={}
    for s,d in mp.items():rep[str(d.relative_to(m))]=patch(t/s,r1,mf,d)
    shutil.copy2(m/'system/etc/fonts.xml',m/'fonts.xml')
    for n in ['SysFont-Regular.ttf','SysSans-En-Regular.ttf','Roboto-Regular.ttf','RobotoFlex-Regular.ttf']:
        p=mf/n
        if p.exists():p.unlink()
    prop=m/'module.prop';lines=[]
    repl={'name=':'name=MFGA ColorOS 16.1 Global Routing TEST','version=':'version=17.1.0-coloros16-global-test1','versionCode=':'versionCode=1717190001','description=':'description=ColorOS 16.1 OEM-aware global routing TEST: Source Sans 3 Latin, LXGW CJK/sys-serif, JetBrains Mono, stock OPlus digit family with zdigit compatibility aliases.'}
    for line in prop.read_text(encoding='utf-8').splitlines():
        for p,v in repl.items():
            if line.startswith(p):line=v;break
        lines.append(line)
    prop.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    (m/'search_dirs.sh').write_text('''#!/system/bin/sh\n. "$MODPATH/lang/lang.sh"\nMODEL="$(getprop ro.product.model 2>/dev/null)"\nSDK="$(getprop ro.build.version.sdk 2>/dev/null)"\nOPLUS="$(getprop ro.build.version.oplusrom 2>/dev/null)"\nui_print "[MFGA] ColorOS OEM-aware routing build"\nui_print "[MFGA] model=$MODEL sdk=$SDK oplus=$OPLUS"\nif [ "$MODEL" != "PJZ110" ] || [ "$SDK" != "36" ]; then\n  ui_print "[!] This TEST build is restricted to PJZ110 / Android 16 (SDK 36)."\n  abort\nfi\ncase "$OPLUS" in V16.1.*) ;; *) ui_print "[!] This TEST build expects ColorOS/Oplus V16.1.x."; abort ;; esac\nui_print "[✓] Keeping bundled per-partition ColorOS font XML topology; generic XML flattening disabled."\n''',encoding='utf-8')
    if a.report:Path(a.report).write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(rep,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
