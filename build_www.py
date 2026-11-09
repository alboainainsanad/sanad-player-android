import re,shutil,os
h=open('/home/claude/sanad-player.html').read()
fonts=''.join(f"@font-face{{font-family:Tajawal;font-weight:{w};font-display:swap;src:url(fonts/tajawal-arabic-{w}-normal.woff2) format('woff2');unicode-range:U+0600-06FF,U+0750-077F,U+0870-088E,U+0890-0891,U+0898-08E1,U+08E3-08FF,U+200C-200E,U+2010-2011,U+204F,U+2E41,U+FB50-FDFF,U+FE70-FE74,U+FE76-FEFC}}@font-face{{font-family:Tajawal;font-weight:{w};font-display:swap;src:url(fonts/tajawal-latin-{w}-normal.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD}}" for w in (400,500,700,800))
h=h.replace('<link rel="preconnect" href="https://fonts.googleapis.com">\n','')
h=re.sub(r'<link href="https://fonts.googleapis.com[^>]+>','<style>'+fonts+'</style>',h)
h=h.replace('https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js','lib/hls.min.js').replace('https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.min.js','lib/mpegts.js').replace('https://cdn.jsdelivr.net/npm/fflate@0.8.2/umd/index.js','lib/fflate.js')
h=h.replace('<meta name="viewport"','<meta name="viewport-orig"') if '<meta name="viewport"' in h else h
h=h.replace('<head>','<head>\n<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">',1)
assert 'googleapis' not in h and 'jsdelivr' not in h
W='/home/claude/apk/sp/www';shutil.rmtree(W,ignore_errors=True);os.makedirs(W)
open(W+'/index.html','w').write(h)
shutil.copytree('/home/claude/mac/app/lib',W+'/lib');shutil.copytree('/home/claude/mac/app/fonts',W+'/fonts')
print('www ok',len(h))
