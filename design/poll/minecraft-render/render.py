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
# ---- scene ----
bg=Image.open('/home/daviseib/Dokumente/VSCode/Velo Client/design/poll/bg.jpg').convert('RGBA').resize((W,H))
im=ImageEnhance.Brightness(bg).enhance(0.85)
shade=Image.new('RGBA',(W,H),(16,16,16,175)); im.alpha_composite(shade)
# header (vanilla screen title + subtitle)
centered(im,'Velo Modules',W//2,16)
centered(im,'Profile: PvP   |   Search: Ctrl+F',W//2,28,(170,170,170))
# creative-style panel with tabs
PX,PY=52,78
tabs=[('clock_00','HUD'),('diamond_sword','PvP'),('ender_eye','Visuals'),('compass_00','Utility'),('feather','Performance'),('repeater','Advanced'),('elytra','Cosmetics')]
SEL=1
for i,(ic,_) in enumerate(tabs):
    if i==SEL: continue
    t=img(f'gui/sprites/container/creative_inventory/tab_top_unselected_{i+1}.png'); im.alpha_composite(t,(PX+i*27,PY-28))
    draw_item(im,ic,PX+i*27+5,PY-28+9)
panel=img('gui/container/creative_inventory/tab_items.png').crop((0,0,195,136)); im.alpha_composite(panel,(PX,PY))
t=img(f'gui/sprites/container/creative_inventory/tab_top_selected_{SEL+1}.png'); im.alpha_composite(t,(PX+SEL*27,PY-28))
draw_item(im,tabs[SEL][0],PX+SEL*27+5,PY-28+9)
text(im,'PvP  -  29 modules',PX+8,PY+6,(64,64,64),False)
im.alpha_composite(img('gui/sprites/container/creative_inventory/scroller.png'),(PX+175,PY+18))
mods=[('book','Enemy Effects',0),('blaze_rod','Combo Counter',1),('redstone','Hit Color',1),('name_tag','Target HUD',0),('iron_sword','Old Animations',1),
('stick','Reach Display',0),('totem_of_undying','Totem Counter',1),('golden_apple','Cooldowns',1),('ender_pearl','Pearl Timer',0),
('dragon_breath','Pot Counter',0),('iron_chestplate','Armor Status',1),('fire_charge','Low Fire',1),('experience_bottle','Damage Numbers',0),('slime_ball','Hit Particles',0),
('goat_horn','Hit Sounds',1),('paper','Auto GG',0),('spectral_arrow','Arrow Counter',1),('clock_00','Fight Recap',0),('bow','Item Position',0),('amethyst_shard','Health Indicator',1),
('compass_00','Direction',0),('feather','Speed',0),('arrow','Custom Crosshair',1),('sugar','FOV',1),('comparator','Attack Indicator',0),('nether_star','Shield Indicator',0),
('writable_book','Height Limit',0),('bundle','Inventory HUD',0),('spyglass','Low Health Warning',1)]
for i,(ic,name,on) in enumerate(mods):
    cx,cy=PX+9+(i%9)*18,PY+18+(i//9)*18
    draw_item(im,ic,cx,cy,glint=bool(on))
# hovered slot highlight + tooltip (Custom Crosshair)
hx,hy=PX+9+4*18,PY+18+2*18
hl=Image.new('RGBA',(16,16),(255,255,255,128)); im.alpha_composite(hl,(hx,hy))
tooltip(im,hx-30,hy+20,[('Custom Crosshair',(255,255,255)),('Aim with your own shape',(170,170,170)),('Enabled',(85,255,85)),('Click: toggle   Right-click: settings',(85,85,85+90))])
# bottom buttons under the panel
button(im,PX,PY+142,95,'Edit HUD',hover=True); button(im,PX+100,PY+142,95,'Locker')
button(im,PX,PY+166,95,'Friends'); button(im,PX+100,PY+166,95,'Done')
# settings column (vanilla options look)
SX=290; centered(im,'Custom Crosshair',SX+95,PY-22)
button(im,SX,PY-6,190,'Enabled: ON'); button(im,SX,PY+18,190,'Shape: Plus')
slider(im,SX,PY+42,190,'Size: 11',0.4); slider(im,SX,PY+66,190,'Gap: 2',0.33)
button(im,SX,PY+90,190,'Colour: White'); button(im,SX,PY+114,93,'Outline: ON'); button(im,SX+97,PY+114,93,'3rd Person: OFF')
button(im,SX,PY+142,190,'Open Crosshair Editor...')
# style tag
tg=Image.new('RGBA',(text_w('STYLE C - MINECRAFT')+10,14),(0,0,0,170)); im.alpha_composite(tg,(8,8)); text(im,'STYLE C - MINECRAFT',13,11)
im=im.resize((W*3,H*3),Image.NEAREST).crop((0,0,1600,900))
im.convert('RGB').save(OUT); print('saved')
