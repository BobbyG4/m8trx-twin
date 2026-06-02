#!/usr/bin/env python3
"""
Build the (fictional) DENVER store assortment from the REAL US Decathlon Shopify catalog.
Real products, real US names/prices/sizes/images, real EANs -> validated EPC encoder.

Source: reference/data/us-catalog/detail/*.json  (482 products, 2,643 variants)
Out:    reference/data/analysis/denver-assortment.csv  +  denver-epcs.csv
Deterministic (seed=42). Korea data not used here (that feeds the separate Seoul store).
"""
import json, glob, csv, re, random, os
from collections import Counter, defaultdict

ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DET=os.path.join(ROOT,'reference/data/us-catalog/detail')
OUT=os.path.join(ROOT,'reference/data/analysis')
SEED=42; TARGET_PIECES=36000
rng=random.Random(SEED)

# product_type -> planogram bucket
TYPEMAP={}
for t in ["Shoes","Snow boots","Cycling Shoes","Sandals","Snowshoe"]: TYPEMAP[t]="footwear"
for t in ["Jacket","Fleece","T-shirt","Trousers/pants","Shorts","Base layer","Down jacket",
          "Long-sleeved t-shirt","Leggings","Short-sleeved jersey","Ski pants","Baselayer bottom",
          "Zip-Off Pants","Long-sleeved jersey","Pullover","Parka","Padded Jacket","Overalls",
          "Windbreaker","Ski suit","Cycling shorts","Cycling bib shorts","3 in 1 jacket","Softshell",
          "Midlayer","Tank","Sweatshirt","Rain Jacket","Rain Poncho","Boxer shorts","Boxers",
          "Sports bra","Skort"]: TYPEMAP[t]="apparel"
for t in ["Socks","Gloves","Beanie","Cap","Hat","Glove liner","Mittens","Fingerless gloves","Towel",
          "Headband","Belt","Neck warmer","Flask","Thermos","Water bottle waist pack","Bottle cap",
          "Bottle cage","Chest strap","Headlamp","Lantern","Water bag","Water pouch","Bladder",
          "Hiking pole","Trail running poles","Running vest"]: TYPEMAP[t]="accessories"
for t in ["Backpack","Travel bag","Sport bag","Belt bag","Quiver","Bag protector"]: TYPEMAP[t]="bag_pack"
for t in ["Road bike","Gravel bike","Mountain bike","Home trainer","Tent","tent","Tent poles","Mattress",
          "Inflatable mattress","Sleeping bag","Sleeping bag liner","Pillow","Foam mat","Shelter","Cookset",
          "Camping stove","Camp bed","Bed base","Hammock","Folding chair","Foot pump","Frame","Seat",
          "Shower","Pad","Cookset"]: TYPEMAP[t]="outdoor"
DROP={"Gift Card","Fastener","Repair tape","zip puller","Zip Slider","Clamping strap","Bottle cap"}
def bucket(t): return None if t in DROP else TYPEMAP.get(t,"outdoor")

# bulky low-depth (display-model) categories
SHALLOW={"outdoor"}  # bikes/tents/furniture -> 1-3
DEPTH={"apparel":16,"accessories":18,"footwear":10,"bag_pack":8,"outdoor":2}

def GF(rows): return [f"GF-R{r}-U{u}" for r in rows for u in range(1,8)]
def GB(rows): return [f"GB-R{r}-U{u}" for r in rows for u in range(1,8)]
PLAN={
 "apparel":     GF([1,2,3,4])+GB([1,2,3,4])+["FR-01","FR-02","FR-03","FR-04"],
 "accessories": ["ACC-01","ACC-02","ACC-03","ACC-04","CO-IR"]+GB([5]),
 "footwear":    [f"PW-0{i}" for i in range(1,9)]+[f"PE-0{i}" for i in range(1,6)]+["FB-01","GA-01","GA-02"],
 "bag_pack":    ["PE-06","PE-07","PE-08"]+GF([5]),
 "outdoor":     GF([6,7,8])+GB([6,7,8]),
}

def ean_to_epc(ean,serial,filt=1,part=6):
    c=int(ean[0:6]); it=int(ean[6:12])
    return f"{int(f'{0x30:08b}{filt:03b}{part:03b}{c:020b}{0:04b}{it:020b}{serial:038b}',2):024X}"

# ── load real US catalog ──
variants=[]
for f in glob.glob(os.path.join(DET,'*.json')):
    try: p=json.load(open(f))['product']
    except: continue
    cat=bucket(p.get('product_type',''))
    if not cat: continue
    opts=[o['name'].lower() for o in p.get('options',[])]
    si=next((i for i,n in enumerate(opts) if 'size' in n), None)
    ci=next((i for i,n in enumerate(opts) if 'col' in n), None)
    imgs=[im['src'] for im in p.get('images',[])]
    for v in p['variants']:
        ean=(v.get('barcode') or '').strip()
        if not (len(ean)==13 and ean.isdigit() and int(ean[:6])<=0xFFFFF and int(ean[6:12])<=0xFFFFF):
            continue
        size=(v.get(f'option{si+1}') if si is not None else '') or 'OS'
        color=(v.get(f'option{ci+1}') if ci is not None else '') or ''
        img=''
        if v.get('featured_image') and v['featured_image'].get('src'): img=v['featured_image']['src']
        elif imgs: img=imgs[0]
        variants.append({"ean":ean,"item_cd":(v.get('sku') or '').strip(),"category":cat,
            "name_en":p['title'],"product_type":p.get('product_type',''),
            "size_us":size,"color":color,"price_usd":v.get('price'),
            "handle":p['handle'],"image":img,"n_images":len(imgs)})
print(f"real US SKUs (encodable): {len(variants)}")
print("by category:", dict(Counter(v['category'] for v in variants)))

# ── depth -> pieces (~TARGET), fixtures, EPCs ──
scale=TARGET_PIECES/sum(DEPTH[v['category']] for v in variants)
rr=defaultdict(int); epcs=[]; used=set(); pieces=0
for v in variants:
    base=DEPTH[v['category']]*scale
    depth=max(1, round(base*rng.uniform(0.6,1.4)))
    if v['category'] in SHALLOW: depth=min(depth, rng.randint(1,3))   # display models
    fx=PLAN[v['category']]; v['fixture']=fx[rr[v['category']]%len(fx)]; rr[v['category']]+=1
    v['depth']=depth
    for _ in range(depth):
        while True:
            s=rng.randint(1000,9_999_999)
            if (v['ean'],s) not in used: used.add((v['ean'],s)); break
        epcs.append({"epc":ean_to_epc(v['ean'],s),"ean":v['ean'],"item_cd":v['item_cd'],
                     "category":v['category'],"fixture":v['fixture']}); pieces+=1

cols=["ean","item_cd","category","product_type","name_en","size_us","color","price_usd","fixture","depth","handle","image","n_images"]
with open(os.path.join(OUT,'denver-assortment.csv'),'w',newline='',encoding='utf-8') as f:
    w=csv.DictWriter(f,fieldnames=cols); w.writeheader()
    for v in variants: w.writerow({k:v.get(k,'') for k in cols})
with open(os.path.join(OUT,'denver-epcs.csv'),'w',newline='',encoding='utf-8') as f:
    w=csv.DictWriter(f,fieldnames=["epc","ean","item_cd","category","fixture"]); w.writeheader(); w.writerows(epcs)

print(f"\nSKUs: {len(variants)}  pieces(EPCs): {pieces}  fixtures used: {len({v['fixture'] for v in variants})}")
print("pieces by category:", dict(Counter(e['category'] for e in epcs)))
print("all SKUs have image:", all(v['image'] for v in variants))
print("samples:")
for v in variants[:5]: print("  ",v['category'],"|",v['name_en'][:32],"|",v['size_us'],v['color'],"| $"+str(v['price_usd']),"|",v['fixture'],"|",v['image'][:60])
