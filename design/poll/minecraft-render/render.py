from PIL import Image, ImageEnhance
import os
SP=os.path.dirname(os.path.abspath(__file__)); T=SP+'/assets/minecraft/textures/'
OUT='/home/daviseib/Dokumente/VSCode/Velo Client/design/poll/style-c-minecraft.png'
W,H=534,300
def img(p): return Image.open(T+p).convert('RGBA')
# ---- font (ascii.png, 8x8 cells) ----
FONT=img('font/ascii.png')
def glyph(ch):
    c=ord(ch)
    if c>255: c=ord('?')
    gx,gy=(c%16)*8,(c//16)*8
    g=FONT.crop((gx,gy,gx+8,gy+8))
    if ch==' ': return g,3
    w=0
    for x in range(8):
        if any(g.getpixel((x,y))[3]>0 for y in range(8)): w=x+1
    return g,w
def tint(g,col):
    r,gg,b=col; out=Image.new('RGBA',g.size,(0,0,0,0)); p=g.load(); o=out.load()
    for y in range(g.size[1]):
        for x in range(g.size[0]):
            a=p[x,y][3]
            if a: o[x,y]=(r,gg,b,a)
    return out
def text_w(s): return sum(glyph(ch)[1]+1 for ch in s)-1
def text(im,s,x,y,col=(255,255,255),shadow=True):
    cx=x
    for ch in s:
        g,w=glyph(ch)
        if shadow: im.alpha_composite(tint(g,tuple(c//4 for c in col)),(cx+1,y+1))
        im.alpha_composite(tint(g,col),(cx,y)); cx+=w+1
    return cx
def centered(im,s,cx,y,col=(255,255,255),shadow=True): text(im,s,cx-text_w(s)//2,y,col,shadow)
# ---- widgets ----
BTN=img('gui/sprites/widget/button.png'); BTNH=img('gui/sprites/widget/button_highlighted.png')
def button(im,x,y,w,label,hover=False,h=20):
    b=BTNH if hover else BTN
    im.alpha_composite(b.crop((0,0,w//2,20)),(x,y)); im.alpha_composite(b.crop((200-(w-w//2),0,200,20)),(x+w//2,y))
    centered(im,label,x+w//2,y+6,(255,255,160) if hover else (255,255,255))
SL=img('gui/sprites/widget/slider.png'); SLH=img('gui/sprites/widget/slider_handle.png')
def slider(im,x,y,w,label,frac):
    im.alpha_composite(SL.crop((0,0,w//2,20)),(x,y)); im.alpha_composite(SL.crop((200-(w-w//2),0,200,20)),(x+w//2,y))
    im.alpha_composite(SLH,(x+int((w-8)*frac),y)); centered(im,label,x+w//2,y+6)
def item(name): return img('item/'+name+'.png')
GLINT=img('misc/enchanted_glint_item.png')
def draw_item(im,name,x,y,glint=False):
    it=item(name); im.alpha_composite(it,(x,y))
    if glint:
        mask=it.split()[3]; g=GLINT.resize((64,64)).crop((10,20,26,36)).convert('RGBA')
        purple=Image.new('RGBA',(16,16),(150,80,255,0)); pp=purple.load(); gp=g.load(); mp=mask.load()
        for yy in range(16):
            for xx in range(16):
                if mp[xx,yy]>0:
                    v=sum(gp[xx,yy][:3])//3; pp[xx,yy]=(170,90,255,min(150,40+v//2))
        im.alpha_composite(purple,(x,y))
def tooltip(im,x,y,lines):
    w=max(text_w(s) for s,_ in lines)+8; h=len(lines)*10+6
    bg=Image.new('RGBA',(w,h),(16,0,16,240)); im.alpha_composite(bg,(x,y))
    for i in range(1,w-1):
        im.putpixel((x+i,y+1),(80,0,255,255)); im.putpixel((x+i,y+h-2),(40,0,127,255))
    for j in range(1,h-1):
        t=j/(h-1); c=(int(80-40*t),0,int(255-128*t),255); im.putpixel((x+1,y+j),c); im.putpixel((x+w-2,y+j),c)
    for i,(s,col) in enumerate(lines): text(im,s,x+4,y+4+i*10+(1 if i else 0),col)

# ---- scene: the normal Velo menu, just with Minecraft's font, buttons, tabs and list background ----
def nine(im,spr,x,y,w,h,b=2):
    sw,sh=spr.size
    for (sx0,sx1,dx0,dx1) in [(0,b,x,x+b),(b,sw-b,x+b,x+w-b),(sw-b,sw,x+w-b,x+w)]:
        for (sy0,sy1,dy0,dy1) in [(0,b,y,y+b),(b,sh-b,y+b,y+h-b),(sh-b,sh,y+h-b,y+h)]:
            if dx1>dx0 and dy1>dy0:
                im.alpha_composite(spr.crop((sx0,sy0,sx1,sy1)).resize((dx1-dx0,dy1-dy0),Image.NEAREST),(dx0,dy0))
bg=Image.open('/home/daviseib/Dokumente/VSCode/Velo Client/design/poll/bg.jpg').convert('RGBA').resize((W,H))
im=ImageEnhance.Brightness(bg).enhance(0.9)
im.alpha_composite(Image.new('RGBA',(W,H),(16,16,16,120)))
# tabs (like Create World), header separator
TAB=img('gui/sprites/widget/tab.png'); TABS=img('gui/sprites/widget/tab_selected.png')
cats=['HUD','PvP','Visuals','Utility','Performance','Advanced']
tw=78; tx0=(W-tw*len(cats))//2
for i,c in enumerate(cats):
    nine(im,TABS if i==1 else TAB,tx0+i*tw,0,tw,24)
    centered(im,c,tx0+i*tw+tw//2,9 if i==1 else 9,(255,255,255) if i==1 else (160,160,160))
LIST=img('gui/inworld_menu_list_background.png')
top,bottom=26,H-33
for yy in range(top,bottom,16):
    for xx in range(0,W,16):
        im.alpha_composite(LIST.crop((0,0,min(16,W-xx),min(16,bottom-yy))),(xx,yy))
HS=img('gui/inworld_header_separator.png'); FS=img('gui/inworld_footer_separator.png')
for xx in range(0,W,32):
    im.alpha_composite(HS.crop((0,0,min(32,W-xx),2)),(xx,24)); im.alpha_composite(FS.crop((0,0,min(32,W-xx),2)),(xx,H-33))
# profile + search row
button(im,W//2-160,34,100,'Profile: PvP')
TF=img('gui/sprites/widget/text_field.png'); nine(im,TF,W//2-52,34,212,20,1)
text(im,'Search modules...',W//2-46,40,(112,112,112),False)
# module options in two columns, like vanilla Options
def option(x,y,name,on):
    w=126
    button(im,x,y,w,'')
    label=name+': '; v='ON' if on else 'OFF'
    tot=text_w(label+v); sx=x+w//2-tot//2
    sx=text(im,label,sx,y+6); text(im,v,sx+1,y+6,(85,255,85) if on else (255,85,85))
    button(im,x+w+2,y,22,'...')
mods=[('Custom Crosshair',1),('Combo Counter',1),('Hit Color',1),('Target HUD',0),('Old Animations',1),('Reach Display',0),('Totem Counter',1),
('Cooldowns',1),('Pot Counter',0),('Armor Status',1),('Low Fire',1),('Damage Numbers',0),('Hit Sounds',1),('Arrow Counter',1)]
cx0=W//2-155
for i,(n,on) in enumerate(mods):
    col=i%2; row=i//2
    option(cx0+col*160,62+row*24,n,on)
# footer buttons
labels=['Edit HUD','Locker','Friends','Done']
bw=98; fx=(W-(bw*4+4*3))//2
for i,l in enumerate(labels):
    button(im,fx+i*(bw+4),H-26,bw,l,hover=(i==0))
tg=Image.new('RGBA',(text_w('STYLE C - MINECRAFT')+10,14),(0,0,0,170)); im.alpha_composite(tg,(4,H-48)); text(im,'STYLE C - MINECRAFT',9,H-45)
im=im.resize((W*3,H*3),Image.NEAREST).crop((0,0,1600,900))
im.convert('RGB').save(OUT); print('saved')
