"""sport_universe.py — Decathlon brand-to-sport-universe taxonomy.

Decathlon organises stores into "univers" (sport universes / departments),
each run by a Sport Leader.  Every passion brand maps 1-to-1 to a sport
universe key.  This module is the single source of truth for that mapping
and provides tier-aware universe lists for the three store formats:
flagship, medium, and city.

All functions are deterministic (given the same seed).  stdlib only.
"""

from __future__ import annotations

import random
from typing import Any

# ---------------------------------------------------------------------------
# Brand → universe key
# ---------------------------------------------------------------------------

# Known Decathlon passion brands and their sport universe.
# Add new brands here as they appear in catalog data; the fallback
# for anything not listed is "general".
BRAND_TO_UNIVERSE: dict[str, str] = {
    # Hiking, Trekking & Camping
    "Quechua": "hike_camp",
    "Forclaz": "hike_camp",
    # Running & Trail
    "Kiprun": "running",
    # Climbing & Mountaineering
    "Simond": "climb",
    # Snow & Ski
    "Wedze": "snow",
    # Cycling (road, touring/city, mountain)
    "Van Rysel": "cycling",
    "Riverside": "cycling",
    "Rockrider": "cycling",
    # Watersports
    "Itiwit": "water",
    # Generic / multi-sport catch-all
    "Decathlon": "general",
}

# ---------------------------------------------------------------------------
# Universe taxonomy
# ---------------------------------------------------------------------------

# Ordered from largest catalog volume to smallest (flagship ordering).
# Each entry carries:
#   key      – stable identifier used throughout the twin
#   label    – human-readable name shown in UIs / logs
#   tiers    – which store formats include this universe
#   seasonal – True if city stores rotate this universe in/out
#
# Sizing rules (mirror real Decathlon formats):
#   flagship : ALL universes present
#   medium   : 5 biggest by catalog volume:
#                hike_camp, running, climb, cycling + snow
#              water is folded into general at this format
#   city     : 3 PERMANENT (hike_camp, running, cycling)
#            + exactly 1 ROTATING SEASONAL from {snow, climb, water}
#              general is always present as a catch-all but is not
#              counted as one of the 4 "content" universes for city logic

UNIVERSES: list[dict[str, Any]] = [
    {
        "key": "hike_camp",
        "label": "Hiking, Trekking & Camping",
        "tiers": {"flagship", "medium", "city"},
        "seasonal": False,
    },
    {
        "key": "running",
        "label": "Running & Trail",
        "tiers": {"flagship", "medium", "city"},
        "seasonal": False,
    },
    {
        "key": "climb",
        "label": "Climbing & Mountaineering",
        "tiers": {"flagship", "medium"},
        "seasonal": True,   # rotates into city stores
    },
    {
        "key": "snow",
        "label": "Snow & Ski",
        "tiers": {"flagship", "medium"},
        "seasonal": True,   # rotates into city stores
    },
    {
        "key": "cycling",
        "label": "Cycling",
        "tiers": {"flagship", "medium", "city"},
        "seasonal": False,
    },
    {
        "key": "water",
        "label": "Watersports",
        "tiers": {"flagship"},
        "seasonal": True,   # rotates into city stores
    },
    {
        "key": "general",
        "label": "General / Multi-sport",
        "tiers": {"flagship", "medium", "city"},
        "seasonal": False,
    },
]

# Derived lookups (built once at import time)
_KEY_TO_UNIVERSE: dict[str, dict[str, Any]] = {u["key"]: u for u in UNIVERSES}

_SEASONAL_KEYS: list[str] = [u["key"] for u in UNIVERSES if u["seasonal"]]
_PERMANENT_CITY_KEYS: list[str] = [
    u["key"] for u in UNIVERSES
    if "city" in u["tiers"] and not u["seasonal"] and u["key"] != "general"
]

# ---------------------------------------------------------------------------
# Public helpers
# ---------------------------------------------------------------------------


def universe_for_brand(brand: str) -> str:
    """Return the universe key for *brand*, falling back to ``"general"``."""
    return BRAND_TO_UNIVERSE.get(brand, "general")


def label_for(key: str) -> str:
    """Return the human-readable label for a universe *key*.

    Raises ``KeyError`` for unknown keys.
    """
    return _KEY_TO_UNIVERSE[key]["label"]


def universes_for_tier(tier: str, seed: int = 0) -> list[str]:
    """Return an ordered list of universe keys active for *tier*.

    Parameters
    ----------
    tier:
        One of ``"flagship"``, ``"medium"``, or ``"city"``.
    seed:
        Integer seed used **only** for city stores to deterministically
        pick the single rotating seasonal universe.  Pass the store's
        sha256-derived seed so each city store shows a different seasonal
        department.  Ignored for flagship and medium.

    Returns
    -------
    list[str]
        Universe keys in presentation order.  ``"general"`` is always last.
        For ``"city"``: 3 permanent keys + 1 seasonal key + ``"general"``.

    Raises
    ------
    ValueError
        For unrecognised tier values.
    """
    tier = tier.lower()

    if tier == "flagship":
        return [u["key"] for u in UNIVERSES]

    if tier == "medium":
        return [u["key"] for u in UNIVERSES if "medium" in u["tiers"]]

    if tier == "city":
        seasonal_key = random.Random(seed).choice(_SEASONAL_KEYS)
        return _PERMANENT_CITY_KEYS + [seasonal_key, "general"]

    raise ValueError(
        f"Unknown tier {tier!r}. Expected one of 'flagship', 'medium', 'city'."
    )


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import collections
    import csv
    import pathlib

    NYC_CSV = (
        pathlib.Path(__file__).parent.parent
        / "reference/data/chain/stores/dec-us-nyc/assortment.csv"
    )

    # ── 1. Brand → universe distribution from real catalog ──────────────────
    print("=" * 60)
    print("Brand → universe distribution (NYC assortment)")
    print("=" * 60)

    per_universe: dict[str, int] = collections.defaultdict(int)
    seen_brands: set[str] = set()

    with open(NYC_CSV, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            brand = row["brand"].strip()
            seen_brands.add(brand)
            per_universe[universe_for_brand(brand)] += 1

    for key in [u["key"] for u in UNIVERSES]:
        count = per_universe.get(key, 0)
        if count:
            print(f"  {label_for(key):<35} {count:>5} units")

    # ── 2. universes_for_tier outputs ───────────────────────────────────────
    print()
    print("=" * 60)
    print("Tier universe lists")
    print("=" * 60)

    for tier_name in ("flagship", "medium"):
        keys = universes_for_tier(tier_name)
        print(f"\n  {tier_name.upper()}:")
        for k in keys:
            tag = " [seasonal]" if _KEY_TO_UNIVERSE[k]["seasonal"] else ""
            print(f"    {k:<12}  {label_for(k)}{tag}")

    print("\n  CITY (seed=0):")
    for k in universes_for_tier("city", seed=0):
        tag = " [SEASONAL PICK]" if k in _SEASONAL_KEYS else ""
        print(f"    {k:<12}  {label_for(k)}{tag}")

    print("\n  CITY (seed=99999):")
    for k in universes_for_tier("city", seed=99999):
        tag = " [SEASONAL PICK]" if k in _SEASONAL_KEYS else ""
        print(f"    {k:<12}  {label_for(k)}{tag}")

    # ── 3. Assertions ────────────────────────────────────────────────────────
    print()
    print("=" * 60)
    print("Assertions")
    print("=" * 60)

    # Every real brand maps to a non-empty universe key
    for brand in seen_brands:
        u = universe_for_brand(brand)
        assert u and isinstance(u, str), (
            f"Brand {brand!r} mapped to empty/invalid universe: {u!r}"
        )
    print("  [OK] Every real brand maps to a non-empty universe key")

    # City tier: exactly 3 permanent + 1 seasonal + general = 5 total
    city_keys = universes_for_tier("city", seed=42)
    assert len(city_keys) == 5, f"Expected 5 city keys, got {len(city_keys)}: {city_keys}"

    permanent_in_city = [k for k in city_keys if k in _PERMANENT_CITY_KEYS]
    seasonal_in_city = [k for k in city_keys if k in _SEASONAL_KEYS]
    general_in_city = [k for k in city_keys if k == "general"]

    assert len(permanent_in_city) == 3, (
        f"Expected 3 permanent city universes, got {len(permanent_in_city)}: {permanent_in_city}"
    )
    assert len(seasonal_in_city) == 1, (
        f"Expected 1 seasonal city universe, got {len(seasonal_in_city)}: {seasonal_in_city}"
    )
    assert len(general_in_city) == 1, "Expected 'general' to be present in city list"
    assert city_keys[-1] == "general", "'general' must be last in city list"
    print("  [OK] City returns exactly 3 permanent + 1 seasonal + general")

    # Seasonal rotation: two different seeds can produce different picks
    keys_s0 = universes_for_tier("city", seed=0)
    keys_s9 = universes_for_tier("city", seed=99999)
    # (Just print whether they differ — not a hard assert since RNG may collide)
    same = keys_s0 == keys_s9
    status = "same (RNG collision for these seeds)" if same else "different (rotation confirmed)"
    print(f"  [INFO] seed=0 vs seed=99999 seasonal picks: {status}")

    # Determinism: same seed always produces the same result
    assert universes_for_tier("city", seed=7) == universes_for_tier("city", seed=7), (
        "universes_for_tier('city', seed=7) is not deterministic"
    )
    print("  [OK] universes_for_tier is deterministic for a given seed")

    # label_for roundtrip
    for u in UNIVERSES:
        assert label_for(u["key"]) == u["label"]
    print("  [OK] label_for roundtrips correctly for all universe keys")

    # Fallback for unknown brand
    assert universe_for_brand("SomeFutureBrand") == "general"
    print("  [OK] Unknown brand falls back to 'general'")

    print()
    print("All assertions passed.")
