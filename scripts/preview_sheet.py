from pathlib import Path
from PIL import Image, ImageDraw
root=Path(__file__).resolve().parents[1]
files=sorted((root/'artifacts/ui-preview').glob('*.png'))
width,height,columns=284,660,4
sheet=Image.new('RGB',(width*columns,height*((len(files)+columns-1)//columns)), '#e7e7eb')
draw=ImageDraw.Draw(sheet)
for i,file in enumerate(files):
    im=Image.open(file).convert('RGB');im.thumbnail((width-16,height-40))
    x=(i%columns)*width;y=(i//columns)*height
    draw.text((x+8,y+8),file.stem,fill='#202024')
    sheet.paste(im,(x+(width-im.width)//2,y+32))
sheet.save(root/'artifacts/ui-contact-sheet.png')
print(root/'artifacts/ui-contact-sheet.png')
