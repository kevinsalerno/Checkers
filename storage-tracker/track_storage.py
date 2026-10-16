#!/usr/bin/env python3
"""Track 10x15 self-storage prices in Union City and Fremont, CA.

Sources: publicstorage.com (each store's own page) and selfstorage.com
(facility pages for the other operators). Appends one row per facility to
prices.csv and prints a summary with the change since the previous run.
"""
import csv
import datetime
import html
import json
import os
import re
import sys
import time
import urllib.request

SIZE = "10x15"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"

PUBLIC_STORAGE = [
    # (city, store id, url slug)
    ("Union City", "192", "union-city"),
    ("Fremont", "165", "fremont"),
    ("Fremont", "282", "fremont"),
    ("Fremont", "383", "fremont"),
    ("Fremont", "451", "fremont"),
]
SELFSTORAGE_COM = [
    # (city, facility slug on selfstorage.com)
    ("Fremont", "allsafe-self-storage-fremont-227321"),
    ("Fremont", "available-self-storage-fremont-37200-post-street-204127"),
    ("Fremont", "cubesmart-self-storage-fremont-153420"),
    ("Fremont", "trunk-space-storage-fremont-43015-osgood-road-244668"),
    ("Fremont", "warm-springs-self-storage-219448"),
    ("Fremont", "west-coast-self-storage-fremont-221350"),
]

HERE = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(HERE, "prices.csv")
FIELDS = ["checked_at", "city", "facility", "address", "monthly_rate",
          "first_month", "promotion", "features", "status", "url"]


def fetch(url):
    """Fetch a page, retrying because these sites sometimes reset connections."""
    for attempt in range(3):
        if attempt:
            time.sleep(10)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as exc:
            print(f"!! {url}: attempt {attempt + 1} failed: {exc}", file=sys.stderr)
    return None


def text_lines(page):
    page = re.sub(r"(?s)<script.*?</script>|<style.*?</style>|<svg.*?</svg>", "", page)
    page = html.unescape(re.sub(r"<[^>]+>", "\n", page))
    return [line.strip() for line in page.split("\n") if line.strip()]


def public_storage(city, store_id, slug):
    url = f"https://www.publicstorage.com/self-storage-ca-{slug}/{store_id}.html"
    row = {"city": city, "facility": f"Public Storage #{store_id}", "url": url}
    page = fetch(url)
    if page is None:
        return dict(row, status="fetch error")
    street = re.search(r'"streetAddress"\s*:\s*"([^"]+)"', page)
    row["address"] = street.group(1) if street else ""
    units = []
    for m in re.finditer(r'<button[^>]*data-unit-size="([^"]+)"[^>]*data-gtmdata="([^"]+)"', page):
        size = html.unescape(m.group(1)).replace("'", "").replace(" ", "")
        if not size.endswith(SIZE):
            continue
        data = json.loads(html.unescape(m.group(2)))
        pricing = data["pricing"]
        units.append((pricing["secondMonthRentAfterPromotion"]["value"],
                      pricing["firstMonthRentAfterPromotion"]["value"],
                      pricing.get("promotionName") or "",
                      ", ".join(data.get("features") or [])))
    if not units:
        # The page lists every size in stock, so no 10x15 card means none available.
        return dict(row, status="no 10x15 listed" if "data-unit-size" in page else "parse error")
    monthly, first, promo, features = min(units)
    return dict(row, monthly_rate=monthly, first_month=first, promotion=promo,
                features=features, status="ok")


def selfstorage_com(city, slug):
    url = f"https://www.selfstorage.com/self-storage/california/{city.lower().replace(' ', '-')}/{slug}/"
    row = {"city": city, "facility": slug, "url": url}
    page = fetch(url)
    if page is None:
        return dict(row, status="fetch error")
    for block in re.findall(r"<script[^>]*ld\+json[^>]*>(.*?)</script>", page, re.S):
        try:
            data = json.loads(block)
        except ValueError:
            continue
        if isinstance(data, dict) and isinstance(data.get("address"), dict):
            row["facility"] = data.get("name") or slug
            row["address"] = data["address"].get("streetAddress", "")
    lines = text_lines(page)
    units = []
    found_any = False
    for i, line in enumerate(lines):
        if not (re.fullmatch(r"\d+(\.\d+)?x\d+(\.\d+)?", line) and lines[i + 1:i + 2] == ["Unit"]):
            continue
        found_any = True
        if line != SIZE:
            continue
        block = []
        for item in lines[i + 2:i + 30]:
            if item.startswith("Reserve") or re.fullmatch(r"\d+(\.\d+)?x\d+(\.\d+)?", item):
                break
            block.append(item)
        prices = [float(p[1:].replace(",", "")) for p in block if re.fullmatch(r"\$[\d,]+\.\d\d", p)]
        if not prices:
            continue
        features = [b for b in block if not re.search(r"[\d$]", b) and b not in ("/ sqft", "ONLY", "LEFT!", "in fees")]
        # When two prices show, the first is the struck-through regular rate.
        promo = f"was ${prices[0]:,.0f}" if len(prices) > 1 else ""
        units.append((prices[-1], prices[-1], promo, ", ".join(features)))
    if not units:
        return dict(row, status="no 10x15 listed" if found_any else "parse error")
    monthly, first, promo, features = min(units)
    return dict(row, monthly_rate=monthly, first_month=first, promotion=promo,
                features=features, status="ok")


def last_rates():
    rates = {}
    if os.path.exists(CSV_PATH):
        with open(CSV_PATH, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                rates[row["url"]] = float(row["monthly_rate"]) if row["monthly_rate"] else None
    return rates


def main():
    previous = last_rates()
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    rows = [public_storage(*f) for f in PUBLIC_STORAGE] + [selfstorage_com(*f) for f in SELFSTORAGE_COM]
    for r in rows:
        r["checked_at"] = now

    new_file = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, "a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, restval="")
        if new_file:
            writer.writeheader()
        writer.writerows(rows)

    print(f"10x15 storage prices, Union City & Fremont CA (checked {now})")
    for r in sorted(rows, key=lambda r: (r["city"], r.get("monthly_rate") or 1e9)):
        label = f"[{r['city']}] {r['facility']} ({r.get('address', '')})"
        if r["status"] != "ok":
            print(f"  {label}: {r['status']}")
            continue
        rate = r["monthly_rate"]
        prev = previous.get(r["url"])
        delta = f" ({rate - prev:+,.0f} vs last check)" if prev is not None and prev != rate else ""
        if r["url"] in previous and prev is None:
            delta = " (newly available)"
        extra = []
        if r["first_month"] != rate:
            extra.append(f"first month ${r['first_month']:,.0f}")
        if r["promotion"]:
            extra.append(r["promotion"])
        extra = f" [{'; '.join(extra)}]" if extra else ""
        print(f"  {label}: ${rate:,.0f}/mo{delta}{extra} — {r['features']}")
    errors = [r for r in rows if r["status"] in ("fetch error", "parse error")]
    return 1 if len(errors) == len(rows) else 0


if __name__ == "__main__":
    sys.exit(main())
