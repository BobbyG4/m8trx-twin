#!/usr/bin/env python3
"""
Build the Decathlon Manhattan store assortment + on-hand EPCs from real Korea data.

Pipeline (see reference/data/INVENTORY-SEEDING-PIPELINE.md):
  1. Pool      = 56k Korea catalog (real EAN + KR name) classified by KR/EN keywords
  2. Velocity  = attach Nov warehouse units by item_cd (real demand, apparel/acc)
  3. Names     = attach English name from Nov by item_cd where available
  4. Select    = ~5,000 SKUs, category-weighted; velocity-ranked where available
  5. Sizes     = relabel embedded size -> US best-effort (real EAN kept)
  6. Depth     = per-category depth, scaled to TARGET_PIECES (~40k)
  7. EPC       = EanToEpc(filter=1, partition=6) per piece, seeded sparse serials
  8. Fixtures  = assign per planogram (category -> STORE-LAYOUT fixture IDs)
  9. Output    = analysis/manhattan-assortment.csv  + analysis/manhattan-epcs.csv

Deterministic: seeded RNG. Real EANs only (no fabricated tags). Korean names where no
English exists are flagged needs_xlate=1 for the translation polish pass.
"""
import csv, re, random, os
from collections import defaultdict, Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF  = os.path.join(ROOT, "reference")
SEED = 42
TARGET_SKUS   = 5000
TARGET_PIECES = 40000          # denser stress-test (Bob 2026-06-02)
rng = random.Random(SEED)

# ── category SKU budgets (apparel/acc ~63%; watches capped to availability) ──
BUDGET = {
    "apparel": 0.40, "accessories": 0.27, "footwear": 0.16,
    "outdoor": 0.08, "fitness": 0.05, "team_sport": 0.025, "watch_gps": 0.015,
}
# per-category avg depth (pieces/SKU); large equipment shallow, socks/apparel deep
DEPTH = {"apparel":7,"accessories":10,"footwear":5,"outdoor":3,
         "fitness":2,"team_sport":2,"watch_gps":2}
# outdoor merges hiking+swim+cycling; map classifier buckets -> planogram buckets
MERGE = {"hiking":"outdoor","swim":"outdoor","cycling":"outdoor",
         "running_app":"apparel","bag_pack":"accessories","protection":"accessories",
         "eyewear":"accessories"}
def GF(rows): return [f"GF-R{r}-U{u}" for r in rows for u in range(1,8)]   # gondola front 7/row
def GB(rows): return [f"GB-R{r}-U{u}" for r in rows for u in range(1,8)]   # gondola back  7/row
PLANOGRAM = {   # category -> real STORE-LAYOUT fixture IDs (149 total)
  "apparel":     GF([1,2,3,4]) + GB([1,2,3,4]) + ["FR-01","FR-02","FR-03","FR-04"],   # 60
  "accessories": ["ACC-01","ACC-02","ACC-03","ACC-04","CO-IR"] + GF([5]) + GB([5]),    # 19
  "footwear":    [f"PW-0{i}" for i in range(1,9)]+[f"PE-0{i}" for i in range(1,6)]+["FB-01","GA-01","GA-02"], # 16
  "outdoor":     GF([7,8]) + GB([7,8]) + ["PE-06","PE-07","PE-08"],                    # 31
  "fitness":     GF([6]),                                                              # 7
  "team_sport":  GB([6]),                                                              # 7
  "watch_gps":   [f"GPS-0{i}" for i in range(1,7)],                                    # 6
}

KW=[
 ('footwear', r'러닝화|운동화|신발|슈즈|화\b|부츠|샌들|키프런|KIPRUN K[SD]|SHOE|TRAINER'),
 ('watch_gps', r'시계|워치|GPS|WATCH|심박'),
 ('eyewear', r'고글|선글라스|안경|아이웨어|GOGGLE|SUNGLASS'),
 ('swim', r'수영|스윔|래쉬가드|수경|SWIM|NABAIJI|아쿠아'),
 ('cycling', r'자전거|싸이클|사이클|바이크|BIKE|CYCL|TRIBAN|ROCKRIDER|BTWIN'),
 ('hiking', r'등산|하이킹|트레킹|등산화|텐트|배낭|캠핑|HIKE|QUECHUA|FORCLAZ|TREK|스키|스노우'),
 ('team_sport', r'축구|농구|배구|테니스|배드민턴|야구|골프|라켓|공\b|볼\b|KIPSTA|ARTENGO|TARMAK|BALL|RACKET'),
 ('fitness', r'피트니스|요가|덤벨|아령|매트|헬스|복근|DOMYOS|YOGA|FITNESS|DUMBBELL'),
 ('bag_pack', r'가방|백팩|배낭|더플|파우치|BAG|BACKPACK'),
 ('accessories', r'양말|장갑|모자|벨트|물통|보틀|타월|수건|밴드|SOCK|GLOVE|CAP|BOTTLE|넥워머|비니'),
 ('protection', r'보호대|가드|패드|무릎|팔꿈치|GUARD|PROTECT|PAD'),
 ('apparel', r'재킷|자켓|티셔츠|반팔|긴팔|반바지|레깅스|후드|팬츠|바지|져지|타이츠|브라|상의|하의|TEE|JACKET|SHORT|LEGGING|PANT|티\b'),
 ('running_app', r'러닝|런닝|조깅|KALENJI|RUN'),
]
def classify(n):
    for cat,pat in KW:
        if re.search(pat, n or '', re.I): return cat
    return 'other'
def planobucket(cat): return MERGE.get(cat, cat)

# ── EPC encoder (clean-room, validated; EPC-ENCODING-DECATHLON.md) ──
def ean_to_epc(ean, serial, filt=1, part=6):
    comp=int(ean[0:6]); item=int(ean[6:12])
    bits=(f"{0x30:08b}{filt:03b}{part:03b}{comp:020b}{0:04b}{item:020b}{serial:038b}")
    return f"{int(bits,2):024X}"

# ── US size relabel (best-effort from embedded token) ──
def to_us_size(name, cat):
    n=name.upper()
    m=re.search(r'\b(2[0-9]{2}|3[0-9]{2})\b', n)        # mondopoint mm (footwear)
    if cat=="footwear" and m:
        mm=int(m.group(1)); us=round((mm-185)/6.6,1)     # ~mm->US men approx
        return f"US {us}"
    m=re.search(r'\b(XS|S|M|L|XL|XXL|2XL|3XL)\b', n)
    if m: return m.group(1).replace("2XL","XXL")
    m=re.search(r'\bEU ?(3[6-9]|4[0-6])\b', n)
    if m: return f"EU{m.group(1)}"
    return "OS"

COLORS=["Black","Blue","Grey","Navy","Red","White","Green","Pink","Orange","Teal"]
def color_of(name):
    for c in COLORS:
        if c.lower() in name.lower() or {"Black":"블랙","Blue":"블루","Grey":"그레이","Red":"레드","White":"화이트","Green":"그린","Pink":"핑크","Navy":"네이비"}.get(c,"~~") in name:
            return c
    return COLORS[hash(name)%len(COLORS)]

PRICE_BAND={"apparel":(15,70),"accessories":(5,35),"footwear":(35,120),
            "outdoor":(20,150),"fitness":(10,90),"team_sport":(8,60),"watch_gps":(20,120)}
def price_of(cat, key):
    lo,hi=PRICE_BAND.get(cat,(10,50)); r=random.Random(key).random()
    p=lo+(hi-lo)*(r**1.6)                                  # skew to lower/mid
    return round(p-0.01,2) if p>1 else 0.99

# ── 1. load catalog ──
cat_rows=[]
with open(os.path.join(REF,'sample_stores/decathlon-korea-raw.csv'),encoding='utf-8',errors='ignore') as f:
    for r in csv.reader(f):
        if len(r)>=4 and re.fullmatch(r'\d{13}', (r[1] or '').strip()):
            ean=r[1].strip(); itemcd=r[2].strip(); name=r[3].strip()
            if int(ean[0:6])>0xFFFFF or int(ean[6:12])>0xFFFFF: continue  # encodable check
            cat_rows.append({"ean":ean,"item_cd":itemcd,"name_kr":name,
                             "cat":planobucket(classify(name))})

# ── 2/3. Nov velocity + English names by item_cd ──
vel={}; en={}
nov=os.path.join(REF,'data/analysis/nov-item-velocity.csv')
if os.path.exists(nov):
    for r in csv.DictReader(open(nov,encoding='utf-8')):
        vel[r['item_cd']]=int(r['qty'])
        if r['name'] and not re.search(r'[가-힣]', r['name']): en[r['item_cd']]=r['name']
for r in cat_rows:
    r["vel"]=vel.get(r["item_cd"],0)
    r["name_en"]=en.get(r["item_cd"],"")
    r["needs_xlate"]=0 if r["name_en"] else 1

# ── 4. select per category budget, velocity-ranked then sampled ──
by_cat=defaultdict(list)
for r in cat_rows:
    if r["cat"] in BUDGET: by_cat[r["cat"]].append(r)
selected=[]
for cat,frac in BUDGET.items():
    want=int(TARGET_SKUS*frac); pool=by_cat[cat]
    pool.sort(key=lambda r:-r["vel"])
    hi=[r for r in pool if r["vel"]>0]                     # real-demand first
    lo=[r for r in pool if r["vel"]==0]; rng.shuffle(lo)
    pick=(hi+lo)[:want]
    selected.extend((cat,r) for r in pick)
    print(f"  {cat:12} want {want:>4}  pool {len(pool):>5}  picked {len(pick):>4}  (w/velocity {min(want,len(hi))})")

# ── 5/6/7/8. size, depth->pieces, EPC, fixture ──
total_target=TARGET_PIECES
raw_depths=[]
for cat,r in selected: raw_depths.append(DEPTH[cat])
scale=total_target/sum(raw_depths)
assort=[]; epcs=[]; used_serial=set(); piece_ct=0; fx_rr=defaultdict(int)
for cat,r in selected:
    size=to_us_size(r["name_en"] or r["name_kr"], cat)
    color=color_of(r["name_en"] or r["name_kr"])
    price=price_of(cat, r["ean"])
    # depth: scaled category base, jittered, US-size weighted (mid sizes deeper)
    base=DEPTH[cat]*scale
    depth=max(1, round(base*rng.uniform(0.6,1.4)))
    # fixture: round-robin across the category's fixtures
    fxlist=PLANOGRAM[cat]; fx=fxlist[fx_rr[cat]%len(fxlist)]; fx_rr[cat]+=1
    assort.append({"ean":r["ean"],"item_cd":r["item_cd"],"category":cat,
                   "name_en":r["name_en"],"name_kr":r["name_kr"],"needs_xlate":r["needs_xlate"],
                   "size_us":size,"color":color,"price_usd":price,"fixture":fx,"depth":depth})
    for _ in range(depth):
        while True:
            s=rng.randint(1000,9_999_999)
            if (r["ean"],s) not in used_serial: used_serial.add((r["ean"],s)); break
        epcs.append({"epc":ean_to_epc(r["ean"],s),"ean":r["ean"],"item_cd":r["item_cd"],
                     "category":cat,"fixture":fx}); piece_ct+=1

# ── 9. output ──
out=os.path.join(REF,'data/analysis')
with open(os.path.join(out,'manhattan-assortment.csv'),'w',newline='',encoding='utf-8') as f:
    w=csv.DictWriter(f,fieldnames=list(assort[0].keys())); w.writeheader(); w.writerows(assort)
with open(os.path.join(out,'manhattan-epcs.csv'),'w',newline='',encoding='utf-8') as f:
    w=csv.DictWriter(f,fieldnames=list(epcs[0].keys())); w.writeheader(); w.writerows(epcs)

print(f"\nSKUs selected: {len(assort)}   pieces(EPCs): {piece_ct}")
print("pieces by category:", dict(Counter(e['category'] for e in epcs)))
print("needs translation:", sum(a['needs_xlate'] for a in assort), "/", len(assort))
print("sample assortment:")
for a in assort[:4]: print("  ",a['category'],a['ean'],a['size_us'],a['color'],f"${a['price_usd']}",a['fixture'],(a['name_en'] or a['name_kr'])[:30])
print("EPC sample:", epcs[0]['epc'], "->", epcs[0]['ean'])
