#!/usr/bin/env python3
"""
Shared config for the Decathlon multi-store CHAIN dataset.

Single source of truth imported by build_chain.py (inventory) and build_staff_roster.py
(org/staff). Defines the 10 stores, per-region localization rules, the org template, and
locale-appropriate name pools. Demo-calibration constants — "realistic, not accurate."

Posture: this produces DATA only. No DB schema, no capability strings, no DB writes.
"""

# ── regions: currency / locale / price localization ───────────────────────────
# price_local = round_to(price_usd * factor, rounding). Factors are demo-calibration,
# not live FX. KRW rounds to nearest 100; EUR/USD to cents.
REGIONS = {
    "US": {"country": "United States", "currency": "USD", "symbol": "$",
           "locale": "en-US", "factor": 1.00, "rounding": 0.01},
    "FR": {"country": "France",        "currency": "EUR", "symbol": "€",
           "locale": "fr-FR", "factor": 0.95, "rounding": 0.01},
    "KR": {"country": "South Korea",   "currency": "KRW", "symbol": "₩",
           "locale": "ko-KR", "factor": 1350.0, "rounding": 100.0},
}

# ── tiers: drive SKU breadth + on-floor/backroom piece count ──────────────────
# sku_count=None means "all encodable master SKUs". target_pieces is per-store EPC volume.
TIERS = {
    "flagship": {"sku_count": None, "label": "Flagship"},
    "large":    {"sku_count": 2400, "label": "Large"},
    "medium":   {"sku_count": 1800, "label": "Medium"},
}

# ── the 10 stores ─────────────────────────────────────────────────────────────
# Real-anchored cities + plausible Decathlon addresses + correct IANA timezones.
# target_pieces overrides per store (flagships vary 34k–36k). sqm reused from the
# established 600 sqm Decathlon-City layout (STORE-LAYOUT.md) unless noted.
# lat/lon = WGS84 decimal degrees, geocoded to each store's actual address (realistic, not
# surveyed). Populates site.latitude/longitude so the geo map can plot every site.
STORES = [
    # US — 3 flagships, 3 distinct US timezones
    {"id": "dec-us-denver", "region": "US", "city": "Denver", "state": "CO",
     "address": "1515 16th Street Mall, Denver, CO 80202", "tz": "America/Denver",
     "lat": 39.74750, "lon": -104.99620, "tier": "flagship", "target_pieces": 36000, "sqm": 600},
    {"id": "dec-us-nyc", "region": "US", "city": "New York", "state": "NY",
     "address": "620 6th Avenue, New York, NY 10011", "tz": "America/New_York",
     "lat": 40.74050, "lon": -73.99420, "tier": "flagship", "target_pieces": 34000, "sqm": 600},
    {"id": "dec-us-sf", "region": "US", "city": "San Francisco", "state": "CA",
     "address": "1 Stockton Street, San Francisco, CA 94108", "tz": "America/Los_Angeles",
     "lat": 37.78570, "lon": -122.40650, "tier": "flagship", "target_pieces": 34000, "sqm": 600},
    # FR — 5 stores, Decathlon home market
    {"id": "dec-fr-paris", "region": "FR", "city": "Paris", "state": "Île-de-France",
     "address": "26 Avenue de Wagram, 75008 Paris", "tz": "Europe/Paris",
     "lat": 48.87660, "lon": 2.29560, "tier": "flagship", "target_pieces": 36000, "sqm": 600},
    {"id": "dec-fr-lyon", "region": "FR", "city": "Lyon", "state": "Auvergne-Rhône-Alpes",
     "address": "112 Cours Charlemagne, 69002 Lyon", "tz": "Europe/Paris",
     "lat": 45.74300, "lon": 4.81800, "tier": "large", "target_pieces": 28000, "sqm": 520},
    {"id": "dec-fr-lille", "region": "FR", "city": "Villeneuve-d'Ascq", "state": "Hauts-de-France",
     "address": "Centre Commercial V2, 59650 Villeneuve-d'Ascq", "tz": "Europe/Paris",
     "lat": 50.62300, "lon": 3.13200, "tier": "large", "target_pieces": 28000, "sqm": 520},
    {"id": "dec-fr-marseille", "region": "FR", "city": "Marseille", "state": "Provence-Alpes-Côte d'Azur",
     "address": "11 Avenue de Bonneveine, 13008 Marseille", "tz": "Europe/Paris",
     "lat": 43.25400, "lon": 5.38500, "tier": "medium", "target_pieces": 18000, "sqm": 420},
    {"id": "dec-fr-bordeaux", "region": "FR", "city": "Bordeaux", "state": "Nouvelle-Aquitaine",
     "address": "Rue du Professeur Georges Jeanneney, 33300 Bordeaux", "tz": "Europe/Paris",
     "lat": 44.88700, "lon": -0.56800, "tier": "medium", "target_pieces": 18000, "sqm": 420},
    # KR — 2 stores
    {"id": "dec-kr-seoul", "region": "KR", "city": "Seoul", "state": "Seoul",
     "address": "416 Gangnam-daero, Gangnam-gu, Seoul 06367", "tz": "Asia/Seoul",
     "lat": 37.49420, "lon": 127.02900, "tier": "large", "target_pieces": 28000, "sqm": 520},
    {"id": "dec-kr-busan", "region": "KR", "city": "Busan", "state": "Busan",
     "address": "30 Centum 1-ro, Haeundae-gu, Busan 48058", "tz": "Asia/Seoul",
     "lat": 35.16900, "lon": 129.13000, "tier": "medium", "target_pieces": 18000, "sqm": 420},
]

# ── tenant ────────────────────────────────────────────────────────────────────
# Tenant-level provisioning target. The admin login is a REAL mailbox (Google OAuth)
# so the operator can actually sign in; the 250 staff use fake @decathlon-demo.com.
TENANT = {
    "name": "M8trxDemo",
    "admin_email": "zenvendemo@gmail.com",
    "admin_display_name": "M8TRX Demo Admin",
    "admin_timezone": "Asia/Seoul",
}

# ── office sites (HQ + regional) ──────────────────────────────────────────────
# First-class SITES with site_type="office": real address + timezone, but NO space,
# zones, fixtures, inventory, or sensors. Every staff member binds to a site (retail
# or office); none are left tenant-scoped. Decathlon's real HQ is in Villeneuve-d'Ascq.
OFFICE_SITES = [
    {"id": "dec-hq-global", "region": "FR", "country": "France", "city": "Villeneuve-d'Ascq",
     "address": "4 Boulevard de Mons, 59650 Villeneuve-d'Ascq", "tz": "Europe/Paris",
     "lat": 50.61100, "lon": 3.17800, "site_type": "office", "office_role": "global_hq"},
    {"id": "dec-us-region", "region": "US", "country": "United States", "city": "New York",
     "address": "110 Fifth Avenue, New York, NY 10011", "tz": "America/New_York",
     "lat": 40.73900, "lon": -73.99300, "site_type": "office", "office_role": "regional_hq"},
    {"id": "dec-fr-region", "region": "FR", "country": "France", "city": "Paris",
     "address": "70 Rue de Tocqueville, 75017 Paris", "tz": "Europe/Paris",
     "lat": 48.88400, "lon": 2.30800, "site_type": "office", "office_role": "regional_hq"},
    {"id": "dec-kr-region", "region": "KR", "country": "South Korea", "city": "Seoul",
     "address": "14 Teheran-ro, Gangnam-gu, Seoul 06236", "tz": "Asia/Seoul",
     "lat": 37.50000, "lon": 127.02700, "site_type": "office", "office_role": "regional_hq"},
]
HQ = OFFICE_SITES[0]                                    # global HQ (back-compat alias)
OFFICE_BY_REGION = {o["region"]: o for o in OFFICE_SITES if o["office_role"] == "regional_hq"}

# ── org template ──────────────────────────────────────────────────────────────
# Global HQ leadership (report to CEO; CEO reports to None).
HQ_ROLES = [
    ("ceo", "Chief Executive Officer"),
    ("cfo", "Chief Financial Officer"),
    ("coo", "Chief Operating Officer"),
    ("cio", "Chief Information Officer / IT Director"),
    ("global_merch_director", "Global Merchandising Director"),
    ("global_lp_director", "Global Loss-Prevention Director"),
    ("global_hr_director", "Global HR Director"),
    ("global_data_lead", "Global Data & Analytics Lead"),
]
# Per region (US/FR/KR) — report to COO.
REGIONAL_ROLES = [
    ("regional_director", "Regional Director"),
    ("regional_ops_manager", "Regional Operations Manager"),
    ("regional_merch_manager", "Regional Merchandising Manager"),
    ("regional_lp_manager", "Regional Loss-Prevention Manager"),
]
# Per store headcount by tier. Store Manager reports to Regional Director; everyone
# else in the store reports up the in-store chain (see build_staff_roster.py).
STORE_HEADCOUNT = {
    "flagship": {"store_manager": 1, "assistant_manager": 2, "department_leader": 5,
                 "sales_associate": 12, "cashier": 4, "stock_logistics": 4, "store_lp": 1},
    "large":    {"store_manager": 1, "assistant_manager": 1, "department_leader": 4,
                 "sales_associate": 9, "cashier": 3, "stock_logistics": 3, "store_lp": 1},
    "medium":   {"store_manager": 1, "assistant_manager": 1, "department_leader": 3,
                 "sales_associate": 6, "cashier": 2, "stock_logistics": 2, "store_lp": 1},
}
DEPARTMENTS = ["Running & Footwear", "Apparel", "Fitness", "Outdoor", "Cycle & Swim"]

ROLE_LABELS = {
    "store_manager": "Store Manager", "assistant_manager": "Assistant Store Manager",
    "department_leader": "Department Leader", "sales_associate": "Sales Associate",
    "cashier": "Cashier", "stock_logistics": "Stock & Logistics", "store_lp": "Store Loss-Prevention",
}

# ~30 of ~200 staff deactivated, deliberately "broken" accounts for surface testing.
INACTIVE_TARGET = 30
INACTIVE_REASONS = ["terminated", "resigned", "on_long_leave", "deactivated", "account_locked"]

# ── locale name pools (deterministic; no network dependency) ──────────────────
# Korean entries are (hangul, romanized) pairs; given names listed given-first but
# rendered family-first per convention in build_staff_roster.py.
NAME_POOLS = {
    "US": {
        "given": ["James", "Mary", "Robert", "Jennifer", "Michael", "Linda", "David", "Patricia",
                  "John", "Elizabeth", "Daniel", "Barbara", "Matthew", "Susan", "Anthony", "Jessica",
                  "Mark", "Sarah", "Steven", "Karen", "Andrew", "Nancy", "Joshua", "Lisa",
                  "Kevin", "Margaret", "Brian", "Betty", "Jason", "Sandra", "Aaron", "Ashley",
                  "Tyler", "Emily", "Jose", "Kimberly", "Adam", "Donna", "Nathan", "Carol"],
        "family": ["Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
                   "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
                   "Thomas", "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson",
                   "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson",
                   "Walker", "Young", "Allen", "King", "Wright", "Scott", "Torres", "Nguyen",
                   "Hill", "Flores"],
    },
    "FR": {
        "given": ["Lucas", "Emma", "Hugo", "Léa", "Louis", "Chloé", "Gabriel", "Manon",
                  "Jules", "Camille", "Arthur", "Sarah", "Raphaël", "Inès", "Nathan", "Julie",
                  "Léo", "Lola", "Théo", "Jade", "Maxime", "Alice", "Antoine", "Louise",
                  "Paul", "Marie", "Quentin", "Anaïs", "Romain", "Clara", "Thomas", "Élise",
                  "Mathis", "Pauline", "Baptiste", "Margaux", "Clément", "Juliette", "Florian", "Céline"],
        "family": ["Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand",
                   "Leroy", "Moreau", "Simon", "Laurent", "Lefebvre", "Michel", "Garcia", "David",
                   "Bertrand", "Roux", "Vincent", "Fournier", "Morel", "Girard", "André", "Mercier",
                   "Dupont", "Lambert", "Bonnet", "François", "Martinez", "Legrand", "Garnier",
                   "Faure", "Rousseau", "Blanc", "Guérin", "Muller", "Henry", "Roussel", "Nicolas", "Perrin"],
    },
    "KR": {
        # (hangul, romanized)
        "given": [("민준", "Min-jun"), ("서연", "Seo-yeon"), ("도윤", "Do-yoon"), ("지우", "Ji-woo"),
                  ("예준", "Ye-jun"), ("하은", "Ha-eun"), ("주원", "Joo-won"), ("지민", "Ji-min"),
                  ("지호", "Ji-ho"), ("수아", "Su-ah"), ("준서", "Jun-seo"), ("지아", "Ji-ah"),
                  ("건우", "Geon-woo"), ("서윤", "Seo-yoon"), ("현우", "Hyun-woo"), ("민서", "Min-seo"),
                  ("우진", "Woo-jin"), ("채원", "Chae-won"), ("선우", "Seon-woo"), ("유진", "Yu-jin"),
                  ("연우", "Yeon-woo"), ("수빈", "Su-bin"), ("정우", "Jung-woo"), ("예은", "Ye-eun"),
                  ("승현", "Seung-hyun"), ("다은", "Da-eun"), ("시우", "Si-woo"), ("아린", "A-rin"),
                  ("준영", "Jun-young"), ("소율", "So-yul")],
        "family": [("김", "Kim"), ("이", "Lee"), ("박", "Park"), ("최", "Choi"), ("정", "Jung"),
                   ("강", "Kang"), ("조", "Cho"), ("윤", "Yoon"), ("장", "Jang"), ("임", "Lim"),
                   ("한", "Han"), ("오", "Oh"), ("서", "Seo"), ("신", "Shin"), ("권", "Kwon"),
                   ("황", "Hwang"), ("안", "Ahn"), ("송", "Song"), ("류", "Ryu"), ("홍", "Hong")],
    },
}


def localize_price(price_usd, region):
    """USD price -> region currency, rounded per region rule. Returns float (or int for KRW)."""
    r = REGIONS[region]
    raw = float(price_usd) * r["factor"]
    step = r["rounding"]
    val = round(raw / step) * step
    if step >= 1:
        return int(round(val))
    return round(val, 2)
