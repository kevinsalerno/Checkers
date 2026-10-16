# Union City / Fremont 10x15 storage price tracker

Tracks monthly prices for **10x15 self-storage units** in Union City and
Fremont, CA.

- **Public Storage:** the Union City store (#192) and the four Fremont stores
  (#165, #282, #383, #451), read from each store's page on publicstorage.com.
- **Other operators:** Allsafe, Available Self Storage, CubeSmart, Trunk Space,
  Warm Springs Self Storage and West Coast Self-Storage, read from their
  listings on selfstorage.com.

Run `python3 track_storage.py` (stdlib only). Each run appends one row per
facility to `prices.csv` and prints the cheapest 10x15 at each facility, with
the change since the previous check.

- `monthly_rate` is the ongoing rate.
- `first_month` is what the first month costs after any move-in promotion.
- A facility with no 10x15 in stock is logged as "no 10x15 listed".

Extra Space, CubeSmart's own site and SpareFoot block scripted requests, so
they aren't read directly.
