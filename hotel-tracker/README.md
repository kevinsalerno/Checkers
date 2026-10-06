# Ginza / Asakusa hot-spring hotel price tracker

Tracks Jalan prices for hotels with **natural hot springs (天然温泉)** in Ginza
and Asakusa for **Dec 4–6, 2026 (2 nights, 2 adults, 1 room)**.

| Area | Hotel | Hot spring |
|---|---|---|
| Ginza | Dormy Inn Premium Ginza | Shippou no Yu (black spring) |
| Ginza | Super Hotel Premier Ginza | Oku-Yugawara no Yu |
| Ginza | Fufu Tokyo Ginza (luxury) | Atami spring, in-room baths |
| Asakusa | Onyado Nono Asakusa | Ryoun no Yu (black spring) |
| Asakusa | Onyado Nono Asakusa Bettei | Ryoten no Yu |

Run `python3 track_prices.py` (stdlib only). Each run appends the cheapest
total (tax incl., whole stay) per hotel to `prices.csv` and prints the change
since the previous check. Women-only rooms are excluded.
