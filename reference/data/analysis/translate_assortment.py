#!/usr/bin/env python3
"""
Translate and normalize manhattan-assortment.csv.
- Cleans name_en for needs_xlate=0 rows (tidy casing/trailing punctuation)
- Translates name_kr Korean product names for needs_xlate=1 rows
- Normalizes size_us: mm->US shoe, EU->US shoe, EU apparel->US apparel label
"""
import csv
import re
import sys

INPUT = "/Users/bob/IdeaProjects/m8trx-twin/reference/data/analysis/manhattan-assortment.csv"
OUTPUT = "/Users/bob/IdeaProjects/m8trx-twin/reference/data/analysis/manhattan-assortment-translated.csv"

# ---------------------------------------------------------------------------
# Size normalisation helpers
# ---------------------------------------------------------------------------

# EU shoe -> US men's (approx EU - 33)
# EU shoe -> US women's (approx EU - 31)
# We infer gender from the product name when possible.

def eu_to_us_men(eu):
    """EU shoe size -> US men's shoe size (approximate)."""
    try:
        eu = float(eu)
    except (TypeError, ValueError):
        return None
    us = eu - 33.0
    # Round to nearest 0.5
    us = round(us * 2) / 2
    if us <= 0:
        return None
    # Format: drop .0 if whole number
    return f"US M {us:.1f}".replace(".0", "").replace("US M ", "US M ")

def eu_to_us_women(eu):
    """EU shoe size -> US women's shoe size (approximate EU - 31)."""
    try:
        eu = float(eu)
    except (TypeError, ValueError):
        return None
    us = eu - 31.0
    us = round(us * 2) / 2
    if us <= 0:
        return None
    return f"US W {us:.1f}".replace(".0", "")

def mm_to_eu(mm):
    """Foot length in mm -> approximate EU shoe size."""
    try:
        mm = float(mm)
    except (TypeError, ValueError):
        return None
    # EU = (foot_length_mm / 6.67) + 2  (Paris point system, rough)
    # Simpler rule-of-thumb: EU ~ mm/6.6 + 1.5, rounded to nearest 0.5
    eu = mm / 6.67 + 2.0
    return round(eu * 2) / 2

def format_us(val):
    """Format a US size float nicely: drop .0, keep .5."""
    if val == int(val):
        return str(int(val))
    return str(val)

def normalize_shoe_size_us(size_raw, name_kr_lower, category):
    """
    Given raw size_us from input and the lowercased Korean name,
    return a normalised US shoe size string.
    """
    if category != "footwear":
        return None  # handled elsewhere

    # Check for mm in name_kr (e.g. "245mm_EU 39" or "270-275mm")
    mm_match = re.search(r'(\d{3})-?(?:\d{3})?mm', name_kr_lower)
    eu_match = re.search(r'eu\s*(\d{2}(?:\.\d)?)', name_kr_lower)

    # Determine gender from name
    # Women markers: Korean 여성/여아/여자 or English women/w suffix in product context
    # Exclude model codes like "KD900 LIGHT M" where M=Men's
    is_women = bool(re.search(r'여성|여아|여자|\bwomens?\b', name_kr_lower))
    # Men markers (overrides is_women if both present, e.g. "여성/남성")  
    is_men_explicit = bool(re.search(r'남성|남자|\bmen\b|\bmens\b|\s[MW]\s*(?:whi|bla|blu|gre|red|bro|gra)', name_kr_lower))
    if is_men_explicit and not bool(re.search(r'여성|여아|여자|\bwomens?\b', name_kr_lower)):
        is_women = False
    is_kids = bool(re.search(r'아동|주니어|어린이|boys|girls|kids|junior|baby', name_kr_lower))

    eu_size = None

    if eu_match:
        eu_size = float(eu_match.group(1))
    elif mm_match:
        mm_val = float(mm_match.group(1))
        eu_size = mm_to_eu(mm_val)

    if eu_size is None:
        # Try parsing existing size_us for EU pattern
        eu_re = re.match(r'EU\s*(\d{2}(?:\.\d)?)', size_raw or "", re.IGNORECASE)
        if eu_re:
            eu_size = float(eu_re.group(1))

    if eu_size is None:
        # Try UK -> EU (UK + 33.5 ≈ EU)
        uk_match = re.search(r'uk\s*(\d{1,2}(?:\.\d)?)', name_kr_lower)
        if uk_match:
            eu_size = float(uk_match.group(1)) + 33.0

    if eu_size is None:
        return None  # can't determine

    if is_kids:
        # Kids: report EU only, no standard US kids conversion here
        eu_fmt = format_us(eu_size)
        return f"EU {eu_fmt}"
    elif is_women:
        us = eu_size - 31.0
        us = round(us * 2) / 2
        if us > 0:
            return f"US W {format_us(us)}"
    else:
        us = eu_size - 33.0
        us = round(us * 2) / 2
        if us > 0:
            return f"US M {format_us(us)}"

    return None

# ---------------------------------------------------------------------------
# Apparel / accessory size normalisation
# ---------------------------------------------------------------------------

EU_APPAREL_TO_US = {
    # Women's EU -> US numeric (approximate)
    32: "XS", 34: "XS", 36: "S", 38: "M", 40: "L", 42: "XL", 44: "XXL", 46: "3XL",
    # Men's same mapping but typically larger
}

def normalize_apparel_size(size_raw, category):
    """Clean up / normalise apparel/accessory size_us."""
    if not size_raw:
        return size_raw

    s = size_raw.strip()

    # Already clean standard sizes
    if s in ("XS", "S", "M", "L", "XL", "XXL", "2XL", "3XL", "4XL", "OS", "UNIQUE"):
        if s == "2XL":
            return "XXL"
        return s

    # EU apparel size (e.g. "EU36", "EU 38")
    eu_app = re.match(r'EU\s*(\d{2})', s, re.IGNORECASE)
    if eu_app:
        eu = int(eu_app.group(1))
        mapped = EU_APPAREL_TO_US.get(eu)
        if mapped:
            return mapped
        # fallback: keep as-is but clean
        return f"EU {eu}"

    # UK apparel sizes - map to US apparel (roughly same as EU for tops)
    # UK shoe sizes handled separately
    uk_app = re.match(r'UK\s*(\d+(?:\.\d+)?)', s, re.IGNORECASE)
    if uk_app and category in ("apparel", "accessories"):
        # UK women tops: 8=XS, 10=S, 12=M, 14=L, 16=XL, 18=XXL
        uk = float(uk_app.group(1))
        mapping = {8:"XS", 10:"S", 12:"M", 14:"L", 16:"XL", 18:"XXL", 20:"XXL"}
        return mapping.get(int(uk), s)

    return s

# ---------------------------------------------------------------------------
# Korean -> English translation dictionary
# (Deterministic lookup for common Korean product terms)
# ---------------------------------------------------------------------------

# Product-type Korean tokens -> English
PRODUCT_TERMS = [
    # Running
    (r'러닝화|조깅화', 'Running Shoe'),
    (r'러닝\s*재킷|러닝용\s*재킷', 'Running Jacket'),
    (r'러닝\s*바지|러닝용\s*바지', 'Running Pants'),
    (r'러닝\s*반바지', 'Running Shorts'),
    (r'러닝\s*레깅스', 'Running Tights'),
    (r'러닝\s*티셔츠|런닝\s*티셔츠', 'Running T-Shirt'),
    (r'러닝\s*셔츠', 'Running Shirt'),
    (r'러닝\s*양말', 'Running Socks'),
    (r'러닝\s*모자', 'Running Cap'),
    (r'러닝\s*장갑', 'Running Gloves'),
    (r'러닝\s*가방', 'Running Bag'),
    (r'러닝용', 'Running'),
    # Hiking / outdoor
    (r'등산화|하이킹\s*부츠|트레킹\s*부츠', 'Hiking Boot'),
    (r'트레킹\s*자켓|하이킹\s*자켓', 'Hiking Jacket'),
    (r'하이킹\s*레인\s*자켓', 'Hiking Rain Jacket'),
    (r'등산용\s*후리스|등산\s*후리스|하이킹\s*플리스', 'Hiking Fleece'),
    (r'트레킹\s*레깅스|마운틴\s*트레킹\s*레깅스', 'Trekking Leggings'),
    (r'마운틴\s*트레킹', 'Mountain Trekking'),
    (r'하이킹\s*신발|트레킹\s*신발', 'Hiking Shoe'),
    (r'트레킹\s*여행\s*자켓', 'Travel Trekking Jacket'),
    (r'자외선\s*차단\s*모자|트레킹.*모자', 'Trekking Sun Hat'),
    (r'하이킹\s*도구\s*\w+\s*다용도통', 'Hiking Multi-Tool Case'),
    # Fitness / gym
    (r'피트니스\s*티셔츠|카디오\s*피트니스\s*티셔츠', 'Cardio Fitness T-Shirt'),
    (r'요가\s*티셔츠|요가\s*유기농면\s*티셔츠', 'Yoga T-Shirt'),
    (r'요가\s*탱크탑|요가\s*심리스\s*탱크탑', 'Yoga Tank Top'),
    (r'요가\s*매트', 'Yoga Mat'),
    (r'레깅스', 'Leggings'),
    (r'필라테스.*레깅스', 'Pilates Leggings'),
    (r'필라테스.*티셔츠', 'Pilates T-Shirt'),
    (r'피트니스\s*워킹화|워킹화', 'Walking Shoe'),
    (r'피트니스\s*백팩', 'Fitness Backpack'),
    (r'인라인.*스케이트', 'Inline Fitness Skate'),
    # Basketball
    (r'농구화', 'Basketball Shoe'),
    # Soccer / football
    (r'축구화', 'Soccer Cleat'),
    (r'축구\s*셔츠|축구\s*반바지|축구\s*스웨트셔츠', 'Soccer'),
    (r'축구공', 'Soccer Ball'),
    # Equestrian
    (r'승마\s*조퍼\s*부츠|조퍼\s*부츠', 'Jodhpur Boot'),
    # Climbing
    (r'암벽화', 'Climbing Shoe'),
    (r'등반\s*바지', 'Climbing Pants'),
    # Cross-training
    (r'크로스\s*트레이닝슈즈|크로스\s*트레이닝\s*신발', 'Cross-Training Shoe'),
    (r'크로스\s*트레이닝\s*티셔츠', 'Cross-Training T-Shirt'),
    (r'크로스\s*트레이닝\s*반바지', 'Cross-Training Shorts'),
    # Generic shoe types
    (r'아쿠아슈즈|아쿠아\s*슈즈', 'Aqua Shoe'),
    (r'스노우\s*하이킹\s*슈즈', 'Snow Hiking Shoe'),
    # Tops / shirts
    (r'긴팔\s*티셔츠', 'Long-Sleeve T-Shirt'),
    (r'반팔\s*티셔츠', 'Short-Sleeve T-Shirt'),
    (r'티셔츠', 'T-Shirt'),
    (r'셔츠', 'Shirt'),
    (r'민소매.*탱크탑|민소매.*베이스\s*레이어', 'Sleeveless Base Layer'),
    (r'스웨터', 'Sweater'),
    (r'스웨트셔츠', 'Sweatshirt'),
    (r'터틀넥', 'Turtleneck'),
    # Bottoms
    (r'반바지', 'Shorts'),
    (r'바지', 'Pants'),
    # Outerwear
    (r'자켓|재킷', 'Jacket'),
    (r'방수.*자켓', 'Waterproof Jacket'),
    (r'레인\s*자켓', 'Rain Jacket'),
    # Accessories
    (r'양말', 'Socks'),
    (r'장갑', 'Gloves'),
    (r'모자', 'Cap'),
    (r'헬멧', 'Helmet'),
    (r'가방', 'Bag'),
    (r'배낭|백팩', 'Backpack'),
    (r'물통', 'Water Bottle'),
    (r'선글라스', 'Sunglasses'),
    (r'허리\s*보호대', 'Lumbar Support Belt'),
    (r'마우스\s*가드', 'Mouth Guard'),
    (r'넘버\s*벨트|레이스용\s*넘버\s*벨트', 'Race Number Belt'),
    # Golf
    (r'골프화|골프\s*신발', 'Golf Shoe'),
    (r'골프\s*셔츠|골프\s*티셔츠', 'Golf Shirt'),
    (r'골프\s*바지', 'Golf Pants'),
    (r'골프\s*스웨터', 'Golf Sweater'),
    (r'골프\s*터틀넥', 'Golf Turtleneck'),
    # Watches
    (r'스톱워치', 'Stopwatch'),
    (r'손목시계|시계', 'Watch'),
    (r'스포츠\s*시계', 'Sports Watch'),
    (r'시계\s*스트랩|스트랩.*시계', 'Watch Strap'),
    (r'시계\s*홀더', 'Watch Holder'),
    # Balls / equipment
    (r'스위스\s*볼', 'Swiss Ball'),
    (r'소프트\s*필라테스\s*볼|필라테스.*볼', 'Soft Pilates Ball'),
    (r'당구대\s*세트|포켓볼.*당구대', 'Billiard Pool Table Set'),
    # Snow / ski
    (r'스노우\s*하이킹', 'Snow Hiking'),
]

# Gender / audience Korean tokens (order matters: more specific patterns first)
GENDER_TERMS = [
    (r'남성/여성|남녀\s*공용|남성\s*/\s*여성', "Unisex"),
    (r'여성\s*/\s*아동|여성/아동', "Women's/Kids'"),
    (r'남성용|남자(?!\s*아이)', "Men's"),
    (r'여성용|여자(?!\s*아이)', "Women's"),
    (r'아동용|어린이용|주니어', "Kids'"),
    (r'성인', "Adult"),
    (r'남아|남자\s*아이', "Boys'"),
    (r'여아|여자\s*아이', "Girls'"),
    (r'유아|아기', "Baby"),
    # English gender markers in mixed names
    (r'\bMens\b', "Men's"),
    (r'\bWomens\b', "Women's"),
    (r'\bBoys\b', "Boys'"),
    (r'\bGirls\b', "Girls'"),
    (r'\bChildrens\b|\bChildren\b|\bJunior\b', "Kids'"),
]

# Attribute / descriptor tokens
ATTR_TERMS = [
    (r'방수', 'Waterproof'),
    (r'보온', 'Thermal'),
    (r'통기성', 'Breathable'),
    (r'썬프로텍트|자외선\s*차단', 'Sun-Protect'),
    (r'심리스', 'Seamless'),
    (r'슬림\s*핏|슬림핏', 'Slim-Fit'),
    (r'레귤러\s*핏|레귤러핏', 'Regular-Fit'),
    (r'루즈\s*핏|루즈핏', 'Loose-Fit'),
    (r'7/8', '7/8'),
    (r'3/4', '3/4'),
    (r'접이식', 'Foldable'),
    (r'마운틴', 'Mountain'),
    (r'컴팩트', 'Compact'),
    (r'3in1', '3-in-1'),
]

def build_translation(name_kr, category):
    """
    Translate a Korean product name to a concise US English product name.
    Strategy:
    1. Find gender/audience prefix
    2. Find brand/model codes (keep as-is)
    3. Find product type
    4. Find key attributes
    5. Strip size/color tokens
    Returns translated string.
    """
    text = name_kr.strip()

    # Extract brand/model codes (uppercase alpha-numeric sequences that look like model codes)
    # e.g. KIPRUN, KALENJI, DOMYOS, QUECHUA, A300, MH500, EKIDEN, etc.
    brand_pattern = re.compile(
        r'\b(KIPRUN|KALENJI|DOMYOS|QUECHUA|FORCLAZ|WEDZE|SIMOND|NEWFEEL|ROCKRIDER|'
        r'GEOLOGIC|NABAIJI|SOLOGNAC|TRIBORD|OLAIAN|FOUGANZA|CAPERLAN|INOFIT|'
        r'W\d{3}[A-Z]*|A\d{3}[A-Z]*|MH\d{3}|NH\d{3}|PW\s*\d{3}|SC\d{3}|SS\d{3}[A-Z]*|'
        r'SH\d{3}|FTS\s*\d{3}|FIT\s*\d+|EDGE\s*LACE-UP|ONRHYTHM\s*\d+|'
        r'ONtraining\s*\d+[A-Z]*|ONstart\s*\d+|ATW\d{3}|ELIOFEET|EKIDEN|'
        r'KIPSTAR|AREETA|ARPENAZ|TREKKING\s*RIVERSIDE\s*\d+|'
        r'KD\d{3}[A-Z]*|KS\d{3}[A-Z]*|'
        r'SHIELD\s*\d+|AGILITY\s*\d+[A-Za-z\s]*(?:HG|MG)?|'
        r'STRONG\s*\d+|ENERGY|ACTIVE|SOFT\s*\d+|FIRST\s*KICK|'
        r'TREK\s*\d+|HIKE\s*\d+|'
        r'(?:F|T|G)\d{3}|'
        r'SWIP|COMFORTEE|HELIUM|THERMIC)\b',
        re.IGNORECASE
    )

    found_brands = brand_pattern.findall(text)
    brand_str = " ".join(b.strip() for b in found_brands) if found_brands else ""

    text_lower = text.lower()

    # 1. Gender
    gender = ""
    for pattern, label in GENDER_TERMS:
        if re.search(pattern, text_lower):
            gender = label
            break

    # 2. Attributes
    attrs = []
    for pattern, label in ATTR_TERMS:
        if re.search(pattern, text_lower):
            attrs.append(label)

    # 3. Product type
    product_type = ""
    for pattern, label in PRODUCT_TERMS:
        if re.search(pattern, text_lower):
            product_type = label
            break

    # If no product type found but we have a brand, use category as fallback
    if not product_type:
        cat_map = {
            "footwear": "Shoe",
            "apparel": "Apparel",
            "accessories": "Accessory",
            "fitness": "Fitness Equipment",
            "outdoor": "Outdoor Gear",
            "team_sport": "Sports Equipment",
            "watch_gps": "Watch",
        }
        product_type = cat_map.get(category, "Product")

    # Build name
    parts = []
    if gender:
        parts.append(gender)

    # Brand/model before product type (if it reads better that way)
    # For Decathlon pattern: "KIPRUN CARE Men's Running T-Shirt"
    if brand_str:
        parts.append(brand_str)

    # Attributes that modify the product type
    attr_prefix = [a for a in attrs if a in ("Waterproof", "Thermal", "Breathable",
                                               "Sun-Protect", "Seamless", "Slim-Fit",
                                               "Regular-Fit", "Loose-Fit", "Mountain",
                                               "Compact", "3-in-1", "Foldable")]
    if attr_prefix:
        parts.extend(attr_prefix)

    parts.append(product_type)

    # Suffix attributes (7/8, 3/4)
    attr_suffix = [a for a in attrs if a in ("7/8", "3/4")]
    if attr_suffix:
        parts[-1] = parts[-1] + " " + "/".join(attr_suffix)

    result = " ".join(parts)

    # Final cleanup: collapse whitespace, fix spacing
    # Deduplicate consecutive repeated words (e.g. "Mountain Mountain Trekking")
    seen_words = []
    for word in result.split():
        if not seen_words or word.lower() != seen_words[-1].lower():
            seen_words.append(word)
    result = ' '.join(seen_words)
    result = re.sub(r'\s+', ' ', result).strip()

    # If result is still just generic ("Product", "Apparel") and name has recognizable
    # English fragments, try to preserve them
    if result in ("Product", "Apparel", "Accessory", "Fitness Equipment",
                  "Outdoor Gear", "Sports Equipment", "Watch", "Shoe"):
        # Look for English words in the Korean name
        english_words = re.findall(r'[A-Za-z][A-Za-z0-9\-/+]*(?:\s+[A-Za-z][A-Za-z0-9\-/+]*)*', text)
        english_words = [w.strip() for w in english_words if len(w) > 2]
        if english_words:
            result = " ".join(english_words[:4])

    return result


def clean_name_en(name_en):
    """Tidy a pre-existing English name: fix casing of trailing commas, clean up."""
    if not name_en:
        return name_en
    # Strip trailing comma/space/punctuation
    name = name_en.rstrip(", ").strip()
    # Remove trailing size/color tokens that are redundant (appear after last comma)
    # e.g. "BEANIE RUNNING WARM+ V2 BLUE GR, No Size" -> "BEANIE RUNNING WARM+ V2 BLUE GR"
    # Keep as-is but strip trailing comma
    return name


def normalize_size(size_raw, name_kr, category):
    """
    Normalize size_us to clean US values.
    Returns (normalized_size_string).
    """
    if not size_raw:
        return size_raw

    s = size_raw.strip()
    name_lower = (name_kr or "").lower()

    # Watches / GPS -> always OS (M/S are watch case sizes, not apparel)
    if category == "watch_gps":
        return "OS"

    # Footwear category
    if category == "footwear":
        # Try to extract from name_kr first (most reliable)
        result = normalize_shoe_size_us(s, name_lower, category)
        if result:
            return result

        # Already a US size string?
        us_match = re.match(r'US\s*(?:M|W)?\s*(\d+(?:\.\d+)?)', s, re.IGNORECASE)
        if us_match:
            return s  # keep as-is if already US format

        # EU size
        eu_match = re.match(r'EU\s*(\d{2}(?:\.\d)?)', s, re.IGNORECASE)
        if eu_match:
            eu = float(eu_match.group(1))
            is_women = bool(re.search(r'여성|여아|w(?:omen)?|womens', name_lower))
            is_kids = bool(re.search(r'아동|주니어|어린이|boys|girls|kids|junior|baby', name_lower))
            if is_kids:
                return f"EU {format_us(eu)}"
            elif is_women:
                us = eu - 31.0
                us = round(us * 2) / 2
                if us > 0:
                    return f"US W {format_us(us)}"
            else:
                us = eu - 33.0
                us = round(us * 2) / 2
                if us > 0:
                    return f"US M {format_us(us)}"

        # "M" for men's shoe (generic, no EU info) -> keep OS or M
        if s == "M":
            return "OS"

        return s

    # Apparel / accessories / fitness / outdoor / team_sport
    # Standard sizes
    size_map = {
        "XS": "XS", "S": "S", "M": "M", "L": "L", "XL": "XL",
        "XXL": "XXL", "2XL": "XXL", "3XL": "3XL", "4XL": "4XL",
        "OS": "OS", "UNIQUE": "OS",
    }
    upper = s.upper()
    if upper in size_map:
        return size_map[upper]

    # EU apparel sizes
    eu_app = re.match(r'EU\s*(\d{2})', s, re.IGNORECASE)
    if eu_app:
        eu = int(eu_app.group(1))
        mapped = EU_APPAREL_TO_US.get(eu)
        if mapped:
            return mapped
        return f"EU {eu}"

    # UK apparel -> US (top sizes)
    uk_match = re.match(r'UK\s*(\d+(?:\.\d+)?)', s, re.IGNORECASE)
    if uk_match and category in ("apparel", "accessories", "outdoor", "fitness"):
        uk = float(uk_match.group(1))
        uk_to_us = {6: "XS", 8: "XS", 10: "S", 12: "M", 14: "L", 16: "XL", 18: "XXL", 20: "XXL"}
        return uk_to_us.get(int(uk), s)

    # US size already formatted (e.g. "US 8.3", "US 12.9")
    if re.match(r'US\s+\d', s, re.IGNORECASE):
        return s

    # Check for mm in size_us (rare for apparel)
    # e.g. accessories like socks "EU39"
    eu_acc = re.match(r'EU\s*(\d{2})-?(\d{2})?', s, re.IGNORECASE)
    if eu_acc:
        return "OS"  # sock ranges -> OS

    return s


# ---------------------------------------------------------------------------
# Main processing
# ---------------------------------------------------------------------------

EU_APPAREL_TO_US = {
    32: "XS", 34: "XS", 36: "S", 38: "M", 40: "L", 42: "XL", 44: "XXL", 46: "3XL",
    48: "3XL",
}


def process_row(row):
    """Process a single CSV row and return the updated row dict."""
    needs_xlate = row["needs_xlate"].strip()
    category = row["category"].strip().lower()
    name_kr = row["name_kr"]
    name_en_orig = row["name_en"]
    size_us_orig = row["size_us"].strip()

    # --- name_en ---
    if needs_xlate == "0":
        name_en_new = clean_name_en(name_en_orig)
    else:
        # Translate Korean name
        name_en_new = build_translation(name_kr, category)
        # If translation produced something very short/generic AND name_kr has
        # recognizable English brand/model that would be better:
        if len(name_en_new) < 4 or name_en_new in ("Men's", "Women's", "Kids'", "Adult"):
            name_en_new = clean_name_en(name_en_orig) or name_en_new

    # --- size_us ---
    size_us_new = normalize_size(size_us_orig, name_kr, category)

    result = dict(row)
    result["name_en"] = name_en_new
    result["size_us"] = size_us_new
    return result


def main():
    with open(INPUT, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames
        rows = list(reader)

    out_rows = []
    translated_count = 0
    errors = []

    for i, row in enumerate(rows):
        try:
            out_row = process_row(row)
            if row["needs_xlate"].strip() == "1":
                translated_count += 1
            out_rows.append(out_row)
        except Exception as e:
            errors.append((i, row, str(e)))
            out_rows.append(row)

    with open(OUTPUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(out_rows)

    print(f"Rows processed: {len(out_rows)}")
    print(f"Names translated (needs_xlate=1): {translated_count}")
    if errors:
        print(f"Errors: {len(errors)}")
        for i, row, e in errors[:5]:
            print(f"  Row {i+2}: {e}")

    # Sample before/after
    print("\n--- Sample before/after (5 rows from needs_xlate=1) ---")
    xlate_rows = [(r, o) for r, o in zip(rows, out_rows) if r["needs_xlate"].strip() == "1"]
    samples = xlate_rows[:5]
    for orig, new in samples:
        print(f"  BEFORE name_kr: {orig['name_kr'][:80]}")
        print(f"  BEFORE name_en: {orig['name_en'][:80]}")
        print(f"  BEFORE size_us: {orig['size_us']}")
        print(f"  AFTER  name_en: {new['name_en'][:80]}")
        print(f"  AFTER  size_us: {new['size_us']}")
        print()


if __name__ == "__main__":
    main()
