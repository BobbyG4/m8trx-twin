# Stock depth, 2026-10-05 to 2026-10-16

Wall demand from the persisted plans (2026-10-05..2026-10-12, all planned sales) and dry-generated plans (2026-10-13..2026-10-16). New = max(0, demand - net supply) + 6. Net supply = free stockroom units less the stockroom units the dry days sell. Sent-only demand = sent persisted sales + dry days.

| store | demand units | of which sent/dry | supply today | net supply | new units | distinct EANs |
|---|---:|---:|---:|---:|---:|---:|
| dec-fr-bordeaux | 1959 | 1878 | 79 | 0 | 6771 | 802 |
| dec-fr-lille | 3566 | 3289 | 200 | 5 | 10142 | 1096 |
| dec-fr-lyon | 2969 | 2733 | 190 | 7 | 7871 | 817 |
| dec-fr-marseille | 1609 | 1492 | 67 | 3 | 5995 | 731 |
| dec-fr-paris | 3893 | 3578 | 302 | 43 | 9017 | 854 |
| dec-kr-busan | 1830 | 1686 | 102 | 2 | 6540 | 785 |
| dec-kr-seoul | 3398 | 3153 | 188 | 7 | 9260 | 977 |
| dec-us-denver | 4548 | 4195 | 335 | 21 | 10416 | 978 |
| dec-us-nyc | 4215 | 3878 | 302 | 27 | 9669 | 909 |
| dec-us-sf | 4243 | 3816 | 284 | 21 | 9997 | 959 |
| **chain** | 32230 | 29698 | 2049 | 136 | 85678 | 8908 |

Serial range used: 1000029568..10999543118 (receiving block 1000000000..11000000000). EANs with no item code: 0.

## Top 10 EANs by new units (chain)

| EAN | name | new units |
|---|---|---:|
| 3583787208861 | Quechua Kids’ MH100 Hiking Rain Poncho – Ages 7–15 | 403 |
| 3608439348181 | Quechua Kids’ NH100 Lace-Up Hiking Shoes | 243 |
| 3583787252956 | Forclaz MT50 Rain Poncho - 10L | 238 |
| 3608390779956 | Quechua Kids’ NH100 Lace-Up Hiking Shoes | 229 |
| 3583787252949 | Forclaz MT50 Rain Poncho - 10L | 177 |
| 3583787788110 | Forclaz Trek 100 Rechargeable 200 Lumens USB Hiking Head Lamp | 169 |
| 3608439256851 | Quechua Kids’ MH150 Waterproof Hiking Jacket – Ages 7–15 | 165 |
| 3608439227998 | Quechua Kids’ MH100 Rain Poncho – Ages 2–6 | 164 |
| 3608439228001 | Quechua Kids’ MH100 Rain Poncho – Ages 2–6 | 164 |
| 3583787729601 | Van Rysel Ultra-Light Carbon Fiber Bottle Cage | 161 |

## Notes

- Margin units: 53448 of 85678 (6 per store and EAN).
- `zone_id` is BLANK: neither keys-2026-10-11.csv nor fixture_ids.csv carries stockroom (BR-*) zones, and layout.json has codes only. The loader joins (site_slug, fixture) to the stockroom zone; `stockroom_fixtures.csv` lists the 62 racks.
