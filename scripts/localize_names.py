#!/usr/bin/env python3
"""
Localize product names EN -> FR + KO for the chain dataset (enrichment pass).

Decathlon names are structured: [Brand] [Gender] [Model] [Descriptors] [Type], e.g.
"Quechua Men's MH500 Waterproof Hiking Shoes". Brands (Quechua, Kiprun, Simond, Wedze,
Van Rysel, Forclaz, …) and model codes (MH500, NH100, MT900) are PRESERVED verbatim — they
are the same worldwide. Gender / product-type / descriptor terms are translated via curated
phrase maps (longest-match-first). Deterministic, offline, no network.

"Realistic, not accurate" — this is a machine gloss for demo display, not certified i18n.
Word order is normalized per language (FR: type then qualifiers; KO: head-final type last).

In  : reference/data/chain/stores/dec-us-denver/assortment.csv   (full 2,586-SKU master names)
Out : reference/data/chain/localization/name-localization.csv     (ean, name_en, name_fr, name_ko)
"""
import csv, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "reference/data/chain/stores/dec-us-denver/assortment.csv")
OUTDIR = os.path.join(ROOT, "reference/data/chain/localization")

# ── phrase maps: (english, french, korean). Multi-word entries are matched before
#    shorter ones (sorted by token count, then length, at load time). ──────────────
GENDERS = [
    ("men's", "Homme", "남성용"), ("women's", "Femme", "여성용"),
    ("unisex", "Mixte", "남녀공용"), ("kids'", "Enfant", "아동용"),
    ("kids", "Enfant", "아동용"), ("adult", "Adulte", "성인용"),
    ("boys'", "Garçon", "남아용"), ("girls'", "Fille", "여아용"),
    ("baby", "Bébé", "유아용"), ("junior", "Junior", "주니어"),
    ("men", "Homme", "남성용"), ("women", "Femme", "여성용"),
]
TYPES = [
    # multi-word / specific first
    ("long-sleeved t-shirt", "T-shirt manches longues", "긴팔 티셔츠"),
    ("long-sleeve t-shirt", "T-shirt manches longues", "긴팔 티셔츠"),
    ("short-sleeved jersey", "Maillot manches courtes", "반팔 저지"),
    ("long-sleeved jersey", "Maillot manches longues", "긴팔 저지"),
    ("cycling bib shorts", "Cuissard à bretelles", "사이클 빕숏"),
    ("cycling shorts", "Cuissard", "사이클 반바지"),
    ("cycling shoes", "Chaussures de vélo", "사이클 슈즈"),
    ("water bottle waist pack", "Porte-bidon ceinture", "물병 웨이스트팩"),
    ("sleeping bag liner", "Drap de sac de couchage", "침낭 라이너"),
    ("sleeping bag", "Sac de couchage", "침낭"),
    ("sleeping pad", "Matelas de sol", "슬리핑 패드"),
    ("zip-off pants", "Pantalon modulable", "짚오프 팬츠"),
    ("fleece vest", "Gilet polaire", "플리스 조끼"),
    ("fleece jacket", "Veste polaire", "플리스 재킷"),
    ("down vest", "Doudoune sans manches", "다운 베스트"),
    ("puffer jacket", "Doudoune", "패딩 재킷"),
    ("down jacket", "Doudoune", "다운 재킷"),
    ("padded jacket", "Veste matelassée", "패딩 재킷"),
    ("rain jacket", "Veste de pluie", "레인 재킷"),
    ("rain poncho", "Poncho de pluie", "우비"),
    ("ski pants", "Pantalon de ski", "스키 바지"),
    ("ski suit", "Combinaison de ski", "스키복"),
    ("snow boots", "Après-ski", "스노우 부츠"),
    ("base layer", "Sous-vêtement technique", "베이스레이어"),
    ("baselayer bottom", "Bas technique", "베이스레이어 하의"),
    ("glove liner", "Sous-gants", "이너 장갑"),
    ("fingerless gloves", "Gants sans doigts", "핑거리스 장갑"),
    ("neck warmer", "Tour de cou", "넥워머"),
    ("sports bra", "Brassière de sport", "스포츠 브라"),
    ("boxer shorts", "Boxer", "사각팬티"),
    ("water bag", "Poche à eau", "물주머니"),
    ("water pouch", "Poche à eau", "물주머니"),
    ("hiking pole", "Bâton de randonnée", "등산 스틱"),
    ("road bike", "Vélo de route", "로드 자전거"),
    ("gravel bike", "Vélo gravel", "그래블 자전거"),
    ("mountain bike", "VTT", "산악자전거"),
    ("home trainer", "Home-trainer", "실내 트레이너"),
    ("travel bag", "Sac de voyage", "여행 가방"),
    ("sport bag", "Sac de sport", "스포츠 가방"),
    ("belt bag", "Banane", "벨트백"),
    ("bag protector", "Housse de sac", "가방 커버"),
    ("foam mat", "Matelas mousse", "폼 매트"),
    ("inflatable mattress", "Matelas gonflable", "에어 매트리스"),
    ("camp bed", "Lit de camp", "캠핑 베드"),
    ("bed base", "Sommier", "베드 베이스"),
    ("camping stove", "Réchaud", "캠핑 스토브"),
    ("tent poles", "Arceaux de tente", "텐트 폴"),
    ("folding chair", "Chaise pliante", "접이식 의자"),
    ("chest strap", "Ceinture thoracique", "흉부 스트랩"),
    ("bottle cage", "Porte-bidon", "보틀 케이지"),
    ("foot pump", "Pompe à pied", "발 펌프"),
    ("3 in 1 jacket", "Veste 3-en-1", "3-in-1 재킷"),
    # single-word types
    ("t-shirt", "T-shirt", "티셔츠"),
    ("sweatshirt", "Sweat", "스웨트셔츠"),
    ("jacket", "Veste", "재킷"),
    ("windbreaker", "Coupe-vent", "바람막이"),
    ("softshell", "Softshell", "소프트쉘"),
    ("midlayer", "Couche intermédiaire", "미드레이어"),
    ("fleece", "Polaire", "플리스"),
    ("pullover", "Pull", "풀오버"),
    ("parka", "Parka", "파카"),
    ("overalls", "Salopette", "멜빵바지"),
    ("trousers/pants", "Pantalon", "바지"),
    ("trousers", "Pantalon", "바지"),
    ("pants", "Pantalon", "바지"),
    ("leggings", "Legging", "레깅스"),
    ("shorts", "Short", "반바지"),
    ("skort", "Jupe-short", "스코트"),
    ("boxers", "Boxer", "사각팬티"),
    ("tank", "Débardeur", "탱크탑"),
    ("jersey", "Maillot", "저지"),
    ("vest", "Gilet", "조끼"),
    ("shirt", "Haut", "셔츠"),
    ("shoes", "Chaussures", "신발"),
    ("snowshoe", "Raquettes", "스노슈즈"),
    ("sandals", "Sandales", "샌들"),
    ("boots", "Bottes", "부츠"),
    ("gloves", "Gants", "장갑"),
    ("mittens", "Moufles", "벙어리장갑"),
    ("socks", "Chaussettes", "양말"),
    ("beanie", "Bonnet", "비니"),
    ("cap", "Casquette", "모자"),
    ("hat", "Chapeau", "모자"),
    ("headband", "Bandeau", "헤어밴드"),
    ("belt", "Ceinture", "벨트"),
    ("backpack", "Sac à dos", "백팩"),
    ("bladder", "Poche à eau", "물주머니"),
    ("mattress", "Matelas", "매트리스"),
    ("pillow", "Oreiller", "베개"),
    ("tent", "Tente", "텐트"),
    ("shelter", "Abri", "셸터"),
    ("hammock", "Hamac", "해먹"),
    ("cookset", "Popote", "쿡세트"),
    ("lantern", "Lanterne", "랜턴"),
    ("headlamp", "Lampe frontale", "헤드램프"),
    ("flask", "Gourde isotherme", "보온병"),
    ("thermos", "Thermos", "보온병"),
    ("towel", "Serviette", "수건"),
    ("frame", "Cadre", "프레임"),
    ("seat", "Selle", "안장"),
    ("shower", "Douche", "샤워기"),
    ("pad", "Tapis", "패드"),
]
DESCRIPTORS = [
    ("water-repellent", "Déperlant", "발수"),
    ("waterproof", "Imperméable", "방수"),
    ("breathable", "Respirant", "통기성"),
    ("insulated", "Isolant", "보온"),
    ("hooded", "à Capuche", "후드"),
    ("long-sleeve", "Manches longues", "긴팔"),
    ("short-sleeve", "Manches courtes", "반팔"),
    ("mid-length", "Mi-long", "미들렝스"),
    ("carbon plate", "Plaque carbone", "카본 플레이트"),
    ("backpacking", "Trekking", "백패킹"),
    ("lightweight", "Léger", "경량"),
    ("seamless", "Sans couture", "심리스"),
    ("merino", "Mérinos", "메리노"),
    ("hiking", "de Randonnée", "등산"),
    ("trekking", "de Trekking", "트레킹"),
    ("running", "de Running", "러닝"),
    ("trail", "Trail", "트레일"),
    ("cycling", "de Vélo", "사이클"),
    ("ski", "de Ski", "스키"),
    ("snow", "Neige", "스노우"),
    ("winter", "Hiver", "겨울"),
    ("thermal", "Thermique", "발열"),
    ("warm", "Chaud", "보온"),
    ("down", "Duvet", "다운"),
    ("puffer", "Doudoune", "패딩"),
    ("padded", "Matelassé", "패딩"),
    ("wool", "Laine", "울"),
    ("mountain", "Montagne", "마운틴"),
    ("travel", "Voyage", "트래블"),
    ("compact", "Compact", "컴팩트"),
    ("foldable", "Pliable", "접이식"),
    ("light", "Léger", "경량"),
    ("max", "Max", "맥스"),
    ("ultra", "Ultra", "울트라"),
    ("mid", "Mi-saison", "미드"),
    ("foam", "Mousse", "폼"),
    ("outdoor", "Outdoor", "아웃도어"),
]


def _sortkey(e):
    return (-len(e[0].split()), -len(e[0]))


GENDERS.sort(key=_sortkey)
TYPES.sort(key=_sortkey)
DESCRIPTORS.sort(key=_sortkey)


def _boundary(phrase):
    # word boundary that tolerates hyphens, slashes, apostrophes inside the phrase
    return re.compile(r"(?<![A-Za-z0-9])" + re.escape(phrase) + r"(?![A-Za-z0-9])", re.IGNORECASE)


_GEN_RE = [(_boundary(e), fr, ko) for e, fr, ko in GENDERS]
_TYP_RE = [(_boundary(e), fr, ko) for e, fr, ko in TYPES]
_DES_RE = [(_boundary(e), fr, ko) for e, fr, ko in DESCRIPTORS]


def localize(name_en):
    """Return (name_fr, name_ko). Preserves brand/model tokens; translates the rest."""
    text = " " + name_en + " "
    gen_fr = gen_ko = None
    typ_fr = typ_ko = None
    des_fr, des_ko = [], []

    def consume(rx, repl=" "):
        nonlocal text
        text = rx.sub(repl, text)

    # gender (first match wins)
    for rx, fr, ko in _GEN_RE:
        if rx.search(text):
            gen_fr, gen_ko = fr, ko
            consume(rx)
            break
    # product type (first / longest match wins)
    for rx, fr, ko in _TYP_RE:
        if rx.search(text):
            typ_fr, typ_ko = fr, ko
            consume(rx)
            break
    # descriptors (collect all, in dictionary order for stable output)
    for rx, fr, ko in _DES_RE:
        if rx.search(text):
            des_fr.append(fr); des_ko.append(ko)
            consume(rx)

    # leftover = preserved brand/model/proper tokens, original order, drop noise
    preserved = [t for t in text.split() if len(t) > 1 or t.isdigit()]
    core = " ".join(preserved)

    # assemble — FR: core + type + descriptors + gender ; KO: core + gender + descriptors + type
    fr_parts = [core] + ([typ_fr] if typ_fr else []) + des_fr + ([gen_fr] if gen_fr else [])
    ko_parts = [core] + ([gen_ko] if gen_ko else []) + des_ko + ([typ_ko] if typ_ko else [])
    name_fr = re.sub(r"\s+", " ", " ".join(p for p in fr_parts if p)).strip()
    name_ko = re.sub(r"\s+", " ", " ".join(p for p in ko_parts if p)).strip()
    return name_fr or name_en, name_ko or name_en


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    rows = list(csv.DictReader(open(SRC, encoding="utf-8")))
    seen, out = {}, []
    for r in rows:
        ean, nm = r["ean"], r["name_en"]
        if ean in seen:
            continue
        if nm not in seen.values():
            pass
        fr, ko = localize(nm)
        seen[ean] = nm
        out.append({"ean": ean, "name_en": nm, "name_fr": fr, "name_ko": ko})

    path = os.path.join(OUTDIR, "name-localization.csv")
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["ean", "name_en", "name_fr", "name_ko"])
        w.writeheader(); w.writerows(out)

    # ── report + coverage ──
    untranslated_fr = sum(1 for o in out if o["name_fr"] == o["name_en"])
    # crude coverage: share of names whose FR differs from EN (i.e. something translated)
    print(f"unique SKUs localized: {len(out)}")
    print(f"FR identical to EN (nothing matched): {untranslated_fr} "
          f"({100*untranslated_fr/len(out):.1f}%)")
    print("\nsamples (EN | FR | KO):")
    for o in out[:12]:
        print(f"  {o['name_en'][:42]:<42} | {o['name_fr'][:40]:<40} | {o['name_ko']}")
    print(f"\nwrote {path}")


if __name__ == "__main__":
    main()
