#!/usr/bin/env python3
"""Track Jalan prices for natural-hot-spring hotels in Ginza and Asakusa.

Stay: Dec 4-6 2026 (2 nights), 2 adults, 1 room. Appends one row per hotel
to prices.csv and prints a summary with the change since the previous run.
"""
import csv
import datetime
import html
import os
import re
import sys
import urllib.request

CHECKIN = datetime.date(2026, 12, 4)
NIGHTS = 2
ADULTS = 2

HOTELS = [
    # (area, jalan id, name, hot spring)
    ("Ginza", "380644", "Dormy Inn Premium Ginza", "Shippou no Yu (black spring)"),
    ("Ginza", "389255", "Super Hotel Premier Ginza", "Oku-Yugawara no Yu"),
    ("Ginza", "333032", "Fufu Tokyo Ginza", "Atami spring, in-room baths"),
    ("Asakusa", "334364", "Onyado Nono Asakusa", "Ryoun no Yu (black spring)"),
    ("Asakusa", "330455", "Onyado Nono Asakusa Bettei", "Ryoten no Yu"),
]

HERE = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(HERE, "prices.csv")
FIELDS = ["checked_at", "area", "hotel", "jalan_id", "cheapest_total_jpy",
          "cheapest_room", "cheapest_plan", "plans_found", "url"]


def plan_url(yad):
    return (f"https://www.jalan.net/yad{yad}/plan/?stayYear={CHECKIN.year}"
            f"&stayMonth={CHECKIN.month}&stayDay={CHECKIN.day}&stayCount={NIGHTS}"
            f"&roomCount=1&adultNum={ADULTS}&dateUndecided=0")


def fetch_lines(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read()
    text = raw.decode("cp932", errors="replace")
    text = re.sub(r"(?s)<script.*?</script>|<style.*?</style>", "", text)
    text = html.unescape(re.sub(r"<[^>]+>", "\n", text))
    return [line.strip() for line in text.split("\n") if line.strip()]


def parse_offers(lines):
    """Return [(total_yen, room, plan)] for every room/plan on the page."""
    offers = []
    plan = room = ""
    for i, line in enumerate(lines):
        if line == "食事：":
            # Plan title sits just above its badges (e.g. オンラインカード決済可).
            for j in range(i - 1, max(i - 6, 0), -1):
                if "決済" not in lines[j] and not re.match(r"\d{4}年", lines[j]):
                    plan = lines[j]
                    break
        elif re.fullmatch(r"ポイント\d+%", line):
            room = lines[i - 1]
        elif (re.fullmatch(r"[\d,]+円～", line) and i + 2 < len(lines)
              and re.fullmatch(r"[\d,]+", lines[i + 1]) and lines[i + 2] == "円"):
            if any(w in room + plan for w in ("レディース", "女性専用")):
                continue  # women-only rooms don't fit 2 adults generally
            offers.append((int(lines[i + 1].replace(",", "")), room, plan))
    return offers


def last_totals():
    totals = {}
    if os.path.exists(CSV_PATH):
        with open(CSV_PATH, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                if row["cheapest_total_jpy"]:
                    totals[row["jalan_id"]] = int(row["cheapest_total_jpy"])
    return totals


def main():
    previous = last_totals()
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    rows = []
    failed = False
    for area, yad, name, spring in HOTELS:
        url = plan_url(yad)
        try:
            offers = parse_offers(fetch_lines(url))
        except Exception as exc:  # keep going so one bad fetch doesn't stop the run
            print(f"!! {name}: fetch failed: {exc}", file=sys.stderr)
            failed = True
            offers = None
        best = min(offers) if offers else None
        rows.append({
            "checked_at": now, "area": area, "hotel": name, "jalan_id": yad,
            "cheapest_total_jpy": best[0] if best else "",
            "cheapest_room": best[1] if best else "",
            "cheapest_plan": best[2] if best else "",
            "plans_found": len(offers) if offers is not None else "error",
            "url": url,
        })

    new_file = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, "a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        if new_file:
            writer.writeheader()
        writer.writerows(rows)

    print(f"Jalan prices, {CHECKIN:%b %d}-{CHECKIN + datetime.timedelta(days=NIGHTS):%b %d %Y}, "
          f"{NIGHTS} nights, {ADULTS} adults, 1 room (checked {now})")
    for r in rows:
        total = r["cheapest_total_jpy"]
        if total == "":
            status = "fetch error" if r["plans_found"] == "error" else "SOLD OUT / no plans"
            print(f"  [{r['area']}] {r['hotel']}: {status}")
            continue
        prev = previous.get(r["jalan_id"])
        delta = "" if prev is None or prev == total else f" ({total - prev:+,} vs last check)"
        print(f"  [{r['area']}] {r['hotel']}: ¥{total:,}{delta} — {r['cheapest_room']}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
