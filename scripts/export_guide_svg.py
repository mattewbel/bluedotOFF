"""Export privacy-safe guide illustrations from the app's shared content; no screenshots."""
from pathlib import Path
import json,html
P=Path(__file__).resolve().parents[1];D=P/'docs/guide';D.mkdir(parents=True,exist_ok=True)
steps=json.loads((P/'app/src/main/assets/guide/steps.json').read_text(encoding='utf-8'))
for name,s in steps.items():
 layout=s.get('layout','settings');height={'permission':294 if name=='nearby' else 250,'developer':300,'split':330,'notification':217,'settings':90+len(s['rows'])*46}.get(layout,228)
 parts=[f'<svg xmlns="http://www.w3.org/2000/svg" width="360" height="{height}" viewBox="0 0 360 {height}" role="img" aria-labelledby="title description">',f'<title id="title">{html.escape(s["title"])}</title>',f'<desc id="description">{html.escape(s["body"])}</desc>',f'<rect width="360" height="{height}" rx="20" fill="#f3f5f9"/>','<g font-family="sans-serif" fill="#1e2431">']
 def rect(x,y,w,h,selected=False):
  parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="#ffffff"'+(' stroke="#356aff" stroke-width="2"' if selected else '')+'/>')
 def text(value,x,y,size=14,center=False,width=306,middle=False):
  # Line-wrap the example labels; no real codes, network names or screenshots.
  lines=[];line='';units=0
  for char in value:
   amount=1 if ord(char)>127 else .55
   if units+amount>width/size and line:lines.append(line);line='';units=0
   line+=char;units+=amount
  if line:lines.append(line)
  # middle: centre the glyphs on y (rows), so Korean labels do not sit high in their box.
  if middle:y-=(len(lines)-1)*(size+2)/2
  for i,line in enumerate(lines):parts.append(f'<text x="{x}" y="{y+i*(size+2)}" font-size="{size}"'+(' text-anchor="middle"' if center else '')+(' dominant-baseline="central"' if middle else '')+f'>{html.escape(line)}</text>')
 def row(value,top,selected=False,badge='',h=42):
  rect(14,top,332,h,selected);text(value,26,top+h/2,width=258 if badge else 306,middle=True)
  if badge in('ON','OFF'):on=badge=='ON';cy=top+h/2;parts.append(f'<rect x="294" y="{cy-11}" width="40" height="22" rx="11" fill="'+('#356aff' if on else '#969ca8')+'"/><circle cx="'+('323' if on else '305')+f'" cy="{cy}" r="8" fill="#ffffff"/>')
  elif badge:text(badge,306,top+h/2,middle=True)
 if layout=='permission':
  if name=='nearby':text('◇',180,38,26,True)
  else:parts.append('<path d="M169 35 L172 30 V25 Q172 15 180 15 Q188 15 188 25 V30 L191 35 Z M177 37 Q180 41 183 37" stroke="#356aff" stroke-width="2" fill="none"/>')
  text(s['screen'],180,70,17,True,width=312)
  top=160 if name=='nearby' else 116
  rect(28,top,304,40,True);text(s['rows'][0],180,top+20,16,True,middle=True);rect(28,top+48,304,40);text(s['rows'][1],180,top+68,16,True,middle=True)
 elif layout=='developer':
  text('‹  '+s['screen'],20,30,16);row(s['rows'][0],46,True,'›');text('↓',180,117,20,True)
  text('‹  '+s['rows'][1],20,146,16);row(s['rows'][2],162);row(s['rows'][3],212,True,'7×')
 elif layout=='notification':
  text(s['screen'],20,32,17);rect(14,48,332,123);text(s['rows'][0],26,78);row(s['rows'][1],105,True,'→',52)
 elif layout=='split':
  text(s['screen'],20,30,16);rect(14,46,332,106);text(s['rows'][0],26,74);text('••••••',180,127,18,True)
  text('—',180,169,14,True);text('bluedotOFF',26,194,16);row(s['rows'][1],206,True,h=34);row(s['rows'][2],250,True,h=34)
 else:
  text('‹  '+s['screen'],20,32,17)
  for i,value in enumerate(s['rows']):row(value,48+i*46,i==s['highlight'],s['badge'] if i==s['highlight'] else '')
 parts+=[f'<text x="20" y="{height-12}" font-size="11" fill="#555555">이동한 화면 예시 · 강조된 곳을 누르세요</text>','</g></svg>']
 (D/(name+'.svg')).write_text('\n'.join(parts),encoding='utf-8')
print('Exported',len(steps),'SVGs from shared guide/steps.json')
