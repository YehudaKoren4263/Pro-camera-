"""Import a curated set of G'MIC film LUTs (MIT, github.com/YahiaAngelo/Film-Luts) into Obscura.

Each .cube (13^3, r fastest) becomes app/src/main/resources/luts/<id>.lut:
  b"OBL1" | size (1 byte) | size^3 * 3 big-endian uint16 (value * 65535).
Near-duplicates (mean colour distance < 3/255 over 512 colours to an already kept LUT) are skipped,
so the picker never offers two looks that look the same.
Usage: python3 -I tools/import_film_luts.py <path-to-Film-Luts/luts> <out-dir>
"""
import json, struct, sys, os, itertools

SRC, OUT = sys.argv[1], sys.argv[2]

# (category, file, id, display name, Hebrew note, grain, vignette) in priority order.
CURATED = [
 ("CAMERA","fujixtransiii/fuji_xtrans_iii_classic_chrome","fx_classic_chrome","Fuji Classic Chrome","הדמיית המצלמה: דוקומנטרי, צבע מאופק, כחול קר.",0.0,0.0),
 ("CAMERA","fujixtransiii/fuji_xtrans_iii_velvia","fx_velvia","Fuji Velvia","הדמיית המצלמה: רוויה של שקופית נוף.",0.0,0.0),
 ("CAMERA","fujixtransiii/fuji_xtrans_iii_provia","fx_provia","Fuji Provia","הדמיית המצלמה: הסטנדרט, נקי ונאמן.",0.0,0.0),
 ("CAMERA","fujixtransiii/fuji_xtrans_iii_astia","fx_astia","Fuji Astia","הדמיית המצלמה: רכה, עור עדין.",0.0,0.0),
 ("CAMERA","fujixtransiii/fuji_xtrans_iii_pro_neg_hi","fx_pro_neg_hi","Fuji Pro Neg Hi","הדמיית המצלמה: נגטיב סטודיו עם ניגוד.",0.0,0.0),
 ("CAMERA","fujixtransiii/fuji_xtrans_iii_pro_neg_std","fx_pro_neg_std","Fuji Pro Neg Std","הדמיית המצלמה: נגטיב סטודיו רך ושטוח.",0.0,0.0),
 ("NEGATIVE","negative_new/kodak_portra_400","portra400","Kodak Portra 400","האגדה של הפורטרטים: עור חם, טונים רכים.",0.25,0.0),
 ("NEGATIVE","negative_new/kodak_portra_160","portra160","Kodak Portra 160","פורטרה עדין: גרעין דק, פסטלי.",0.15,0.0),
 ("NEGATIVE","negative_new/kodak_portra_800","portra800","Kodak Portra 800","פורטרה לילה: חם, ניגוד גבוה יותר.",0.35,0.0),
 ("NEGATIVE","kodak_ektar_100","ektar100","Kodak Ektar 100","נגטיב רווי וחד: צבע עז, גרעין עדין.",0.12,0.0),
 ("NEGATIVE","negative_new/fuji_400h","fuji400h","Fuji Pro 400H","אוורירי וטורקיז, אהוב בחתונות.",0.25,0.0),
 ("NEGATIVE","negative_new/fuji_160c","fuji160c","Fuji Pro 160C","נקי ורווי במעט, ירוקים חיים.",0.15,0.0),
 ("NEGATIVE","negative_new/fuji_800z","fuji800z","Fuji Pro 800Z","חם וגרעיני, לאור חלש.",0.35,0.0),
 ("NEGATIVE","negative_old/fuji_superia_400","superia400","Fuji Superia 400","הנגטיב היומיומי: ירוקים קרירים.",0.3,0.0),
 ("NEGATIVE","fuji_superia_x-tra_800","superia800","Fuji Superia X-Tra 800","גרעיני ורווי, אווירת שנות ה-2000.",0.4,0.0),
 ("NEGATIVE","agfa_vista_200","vista200","Agfa Vista 200","אדומים חמים, נוסטלגיה אירופית.",0.3,0.0),
 ("NEGATIVE","kodak_elite_color_400","elite400","Kodak Elite Color 400","צרכני חם ורווי.",0.3,0.0),
 ("NEGATIVE","lomography_redscale_100","redscale","Lomography Redscale","נגטיב הפוך: הכול אדום-כתום.",0.35,0.15),
 ("SLIDE","fuji_velvia_50","velvia50","Fuji Velvia 50","השקופית של צלמי הנוף: רוויה קיצונית.",0.1,0.0),
 ("SLIDE","fuji_provia_100f","provia100f","Fuji Provia 100F","שקופית מאוזנת ונאמנה.",0.1,0.0),
 ("SLIDE","fuji_astia_100f","astia100f","Fuji Astia 100F","שקופית רכה לפורטרטים.",0.1,0.0),
 ("SLIDE","kodak_kodachrome_64","kodachrome64","Kodak Kodachrome 64","הקלאסיקה: אדומים חמים, כחולים עמוקים.",0.15,0.0),
 ("SLIDE","kodak_kodachrome_25","kodachrome25","Kodak Kodachrome 25","קודכרום עדין וחד.",0.08,0.0),
 ("SLIDE","kodak_ektachrome_100_vs","ektachrome_vs","Kodak Ektachrome 100 VS","שקופית רוויה וקרירה.",0.1,0.0),
 ("SLIDE","kodak_elite_chrome_400","elitechrome400","Kodak Elite Chrome 400","שקופית צרכנית גרעינית.",0.25,0.0),
 ("SLIDE","agfa_precisa_100","precisa100","Agfa Precisa 100","שקופית אירופית קרירה.",0.15,0.0),
 ("SLIDE","lomography_x-pro_slide_200","xpro200","Lomography X-Pro 200","פיתוח צולב: צהוב-ירוק וכחול.",0.3,0.2),
 ("INSTANT","polaroid_669","polaroid669","Polaroid 669","אינסטנט פילינג: דהוי וחם.",0.2,0.25),
 ("INSTANT","polaroid_690","polaroid690","Polaroid 690","אינסטנט רווי, ניגוד בינוני.",0.2,0.25),
 ("INSTANT","polaroid_polachrome","polachrome","Polaroid Polachrome","שקופית אינסטנט נדירה, צבעים דחוסים.",0.25,0.2),
 ("INSTANT","instant_consumer/polaroid_px-70","px70","Polaroid PX-70","אינסטנט מודרני: עמום וחלומי.",0.2,0.3),
 ("INSTANT","instant_consumer/polaroid_px-680","px680","Polaroid PX-680","אינסטנט מודרני חם.",0.2,0.3),
 ("INSTANT","instant_pro/fuji_fp-100c","fp100c","Fuji FP-100C","אינסטנט פילינג של פוג'י: ירוקים-כחולים.",0.2,0.2),
 ("CINEMA","print/kodak_2383_constlmap","kodak2383","Kodak 2383 Print","פילם הקרנה קולנועי: הלוק של בתי הקולנוע.",0.15,0.1),
 ("CINEMA","print/kodak_2393_constlmap","kodak2393","Kodak 2393 Print","פילם הקרנה רווי וחד יותר.",0.15,0.1),
 ("CINEMA","print/fuji_3513_constlmap","fuji3513","Fuji 3513 Print","פילם הקרנה של פוג'י: קריר וירקרק.",0.15,0.1),
 ("MONO","fujixtransiii/fuji_xtrans_iii_acros","fx_acros","Fuji Acros","הדמיית המצלמה: שחור-לבן עשיר.",0.3,0.0),
 ("MONO","fujixtransiii/fuji_xtrans_iii_acros+r","fx_acros_r","Fuji Acros + R","עם פילטר אדום: שמיים דרמטיים.",0.3,0.0),
 ("MONO","fujixtransiii/fuji_xtrans_iii_acros+ye","fx_acros_ye","Fuji Acros + Ye","עם פילטר צהוב: ניגוד עדין בשמיים.",0.3,0.0),
 ("MONO","negative_new/kodak_tri-x_400","trix400","Kodak Tri-X 400","הקלאסיקה של עיתונות הרחוב.",0.55,0.0),
 ("MONO","bw/ilford_hp_5_plus_400","hp5","Ilford HP5 Plus 400","שחור-לבן רך וגרעיני.",0.5,0.0),
 ("MONO","bw/ilford_delta_3200","delta3200","Ilford Delta 3200","לילה: גרעין כבד, שחורים עמוקים.",0.8,0.1),
 ("MONO","bw/kodak_t-max_100","tmax100","Kodak T-Max 100","חד ונקי, גרעין כמעט בלתי נראה.",0.15,0.0),
 ("MONO","bw/ilford_pan_f_plus_50","panf50","Ilford Pan F 50","טונים עדינים, עור חלק.",0.12,0.0),
 ("MONO","bw/rollei_ortho_25","ortho25","Rollei Ortho 25","אורתוכרומטי: אדום כהה, שמיים בהירים.",0.15,0.0),
 ("MONO","bw/kodak_hie_hs_infra","hie_infra","Kodak HIE Infrared","אינפרא-אדום: עלווה לבנה, שמיים שחורים.",0.5,0.2),
]

def find(rel):
    for cand in (rel, *[f"{d}/{rel}" for d in os.listdir(SRC)]):
        p = os.path.join(SRC, cand + ".cube")
        if os.path.exists(p): return p
    raise FileNotFoundError(rel)

def load(path):
    n=None; vals=[]
    for line in open(path):
        t=line.strip()
        if not t or t[0]=="#" or t[0].isalpha():
            if t.startswith("LUT_3D_SIZE"): n=int(t.split()[1])
            continue
        vals.append(tuple(float(x) for x in t.split()))
    assert n and len(vals)==n**3, path
    return n, vals

def sample(n, vals, r, g, b):
    f=lambda v: v*(n-1)
    fr,fg,fb=f(r),f(g),f(b); r0,g0,b0=[min(int(x),n-2) for x in (fr,fg,fb)]
    dr,dg,db=fr-r0,fg-g0,fb-b0; out=[0.0,0.0,0.0]
    for k in range(8):
        ir,ig,ib=r0+(k&1),g0+((k>>1)&1),b0+((k>>2)&1)
        w=(dr if k&1 else 1-dr)*(dg if (k>>1)&1 else 1-dg)*(db if (k>>2)&1 else 1-db)
        v=vals[(ib*n+ig)*n+ir]
        for c in range(3): out[c]+=w*v[c]
    return out

palette=[(r/7,g/7,b/7) for r in range(8) for g in range(8) for b in range(8)]
os.makedirs(OUT, exist_ok=True)
kept=[]; manifest=[]
for cat,rel,lid,name,note,grain,vig in CURATED:
    n,vals=load(find(rel))
    resp=[sample(n,vals,*p) for p in palette]
    dup=None
    for (oid,oresp) in kept:
        d=sum(abs(a-b) for x,y in zip(resp,oresp) for a,b in zip(x,y))/(len(resp)*3)*255
        if d<3.0: dup=(oid,d); break
    if dup: print(f"skip {lid}: too close to {dup[0]} ({dup[1]:.1f})"); continue
    kept.append((lid,resp))
    with open(os.path.join(OUT,lid+".lut"),"wb") as fh:
        fh.write(b"OBL1"); fh.write(bytes([n]))
        for v in vals:
            for c in v: fh.write(struct.pack(">H", max(0,min(65535,round(c*65535)))))
    manifest.append(dict(id=lid,name=name,note=note,category=cat,grain=grain,vignette=vig,source=os.path.relpath(find(rel),SRC)))
json.dump(manifest, open(os.path.join(OUT,"manifest.json"),"w"), ensure_ascii=False, indent=1)
print(f"kept {len(manifest)} of {len(CURATED)}")
