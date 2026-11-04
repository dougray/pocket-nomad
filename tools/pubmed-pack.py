#!/usr/bin/env python3
"""Builds the "PubMed: Survival Medicine" pack: guideline and systematic-review
abstracts on emergency, wilderness and disaster medicine from PubMed, as a folder
that `nomad-index zim` turns into a ZIM book.

    python3 -I tools/pubmed-pack.py <work-folder>

Uses NCBI's free E-utilities (no key needed, at most 3 requests a second). Fetched
batches are cached in <work-folder>/cache, so an interrupted run picks up where it
stopped. Abstracts may be copyrighted by their publishers: build the pack for your
own use and don't redistribute it.
"""
import hashlib
import html
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import date

EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/"
TOOL = "pocket-nomad"
BATCH = 400

# MeSH major topics that matter when help is far away: (heading, friendly name,
# include narrower headings). Broad topics full of hospital care (hip-fracture
# surgery, brain bleeds, surgical wound infection) use the heading alone.
TOPIC_LIST = [
    ("Emergency Treatment", "Emergency treatment", False),
    ("First Aid", "First aid", True),
    ("Wilderness Medicine", "Wilderness medicine", True),
    ("Disasters", "Disasters", True),
    ("Cardiopulmonary Resuscitation", "CPR", True),
    ("Airway Obstruction", "Choking", False),
    ("Hemorrhage", "Bleeding", False),
    ("Tourniquets", "Tourniquets", True),
    ("Shock", "Shock", False),
    ("Anaphylaxis", "Anaphylaxis", True),
    ("Wounds, Penetrating", "Penetrating wounds", True),
    ("Lacerations", "Cuts and lacerations", True),
    ("Wound Infection", "Wound infection", False),
    ("Burns", "Burns", True),
    ("Fractures, Bone", "Fractures", False),
    ("Joint Dislocations", "Dislocations", True),
    ("Sprains and Strains", "Sprains and strains", True),
    ("Snake Bites", "Snake bites", True),
    ("Bites and Stings", "Bites and stings", True),
    ("Hypothermia", "Hypothermia", True),
    ("Frostbite", "Frostbite", True),
    ("Heat Stress Disorders", "Heat illness", True),
    ("Altitude Sickness", "Altitude sickness", True),
    ("Drowning", "Drowning", True),
    ("Lightning Injuries", "Lightning injuries", True),
    ("Dehydration", "Dehydration", True),
    ("Fluid Therapy", "Fluid therapy and rehydration", False),
    ("Diarrhea", "Diarrhea", True),
    ("Water Purification", "Water purification", True),
    ("Waterborne Diseases", "Waterborne diseases", True),
    ("Sanitation", "Sanitation", True),
    ("Vector Borne Diseases", "Insect- and tick-borne diseases", True),
    ("Poisoning", "Poisoning", False),
    ("Mushroom Poisoning", "Mushroom poisoning", True),
    ("Plants, Toxic", "Poisonous plants", True),
]
TOPICS = {heading: name for heading, name, _ in TOPIC_LIST}

FILTERS = ("hasabstract AND english[la]"
           " AND (practice guideline[pt] OR guideline[pt] OR systematic review[pt])")


def topic_query(heading, explode):
    return f'"{heading}"[Majr{"" if explode else ":NoExp"}] AND {FILTERS}'


QUERY = " OR ".join(topic_query(h, e) for h, _, e in TOPIC_LIST)  # identifies the cache

STYLE = """
:root { color-scheme: light dark; --accent: #c25b00; --muted: #6b6b6b; }
@media (prefers-color-scheme: dark) { :root { --accent: #fc9712; --muted: #a0a0a0; } }
body { font: 17px/1.5 -apple-system, system-ui, sans-serif; margin: 16px; max-width: 46em; }
h1 { font-size: 1.35em; line-height: 1.25; margin: 0 0 .4em; }
h2 { font-size: 1em; text-transform: uppercase; letter-spacing: .04em; color: var(--accent); margin: 1.2em 0 .3em; }
.cite, .note, .topics { color: var(--muted); font-size: .9em; }
.badge { display: inline-block; font-size: .75em; font-weight: 600; padding: 2px 8px; border-radius: 10px;
         border: 1px solid var(--accent); color: var(--accent); margin-bottom: .6em; }
.note { border-left: 3px solid var(--accent); padding-left: 10px; }
ul.list { padding-left: 1.1em; } ul.list li { margin: .35em 0; }
a { color: var(--accent); }
"""


def get(endpoint, **params):
    params["tool"] = TOOL
    # POST, because a batch of IDs is too long for a URL.
    body = urllib.parse.urlencode(params).encode()
    for attempt in range(5):
        try:
            with urllib.request.urlopen(EUTILS + endpoint, data=body, timeout=120) as r:
                data = r.read()
            time.sleep(0.4)  # NCBI allows 3 requests a second without a key
            return data
        except Exception as e:  # network hiccup or 429: back off and retry
            wait = 2 ** attempt
            print(f"  retrying in {wait}s ({e})", file=sys.stderr)
            time.sleep(wait)
    raise SystemExit(f"error: could not fetch {endpoint}")


def search_ids(query, first, last):
    """PubMed returns at most 10,000 IDs per search, so split by publication year."""
    term = f"({query}) AND {first}:{last}[dp]"
    r = json.loads(get("esearch.fcgi", db="pubmed", term=term, retmode="json", retmax=9999), strict=False)["esearchresult"]
    if int(r["count"]) <= 9999 or first == last:
        return r["idlist"]
    mid = (first + last) // 2
    return search_ids(query, first, mid) + search_ids(query, mid + 1, last)


def text(el):
    return " ".join("".join(el.itertext()).split()) if el is not None else ""


def parse(article):
    cit = article.find("MedlineCitation")
    art = cit.find("Article")
    pmid = text(cit.find("PMID"))
    title = text(art.find("ArticleTitle")).rstrip(".") or f"PubMed {pmid}"
    sections = []
    for ab in art.findall("Abstract/AbstractText"):
        sections.append((ab.get("Label") or "", text(ab)))
    journal = text(art.find("Journal/ISOAbbreviation")) or text(art.find("Journal/Title"))
    year = text(art.find("Journal/JournalIssue/PubDate/Year")) or text(art.find("Journal/JournalIssue/PubDate/MedlineDate"))[:4]
    names = []
    for a in art.findall("AuthorList/Author"):
        last, initials, collective = text(a.find("LastName")), text(a.find("Initials")), text(a.find("CollectiveName"))
        names.append(f"{last} {initials}".strip() or collective)
    authors = ", ".join(names[:3]) + (" et al." if len(names) > 3 else "")
    types = [text(t) for t in art.findall("PublicationTypeList/PublicationType")]
    kind = "Guideline" if any("Guideline" in t for t in types) else "Systematic review"
    withdrawn = title.upper().startswith(("WITHDRAWN", "RETRACTED")) or "Retracted Publication" in types
    ids = {i.get("IdType"): text(i) for i in article.findall("PubmedData/ArticleIdList/ArticleId")}
    return {"pmid": pmid, "title": title, "sections": sections, "journal": journal, "year": year,
            "authors": authors, "kind": kind, "withdrawn": withdrawn, "doi": ids.get("doi"), "pmc": ids.get("pmc")}


def page(title, body):
    return (f"<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'>"
            f"<title>{html.escape(title)}</title><style>{STYLE}</style></head><body>{body}</body></html>")


def article_html(p):
    e = html.escape
    body = [f"<span class='badge'>{e(p['kind'])}</span>", f"<h1>{e(p['title'])}</h1>",
            f"<p class='cite'>{e(p['authors'])}. <i>{e(p['journal'])}</i> {e(p['year'])}. PMID {e(p['pmid'])}</p>"]
    for label, para in p["sections"]:
        if label:
            body.append(f"<h2>{e(label.title())}</h2>")
        body.append(f"<p>{e(para)}</p>")
    if p["topics"]:
        links = ", ".join(f"<a href='topic/{slug(t)}'>{e(t)}</a>" for t in p["topics"])
        body.append(f"<p class='topics'>Topics: {links}</p>")
    online = [f"<a href='https://pubmed.ncbi.nlm.nih.gov/{p['pmid']}/'>PubMed</a>"]
    if p["pmc"]:
        online.append(f"<a href='https://pmc.ncbi.nlm.nih.gov/articles/{e(p['pmc'])}/'>free full text (PMC)</a>")
    if p["doi"]:
        online.append(f"<a href='https://doi.org/{e(p['doi'])}'>publisher</a>")
    body.append(f"<p class='note'>Research summary for clinicians, not step-by-step instructions. "
                f"Online: {' · '.join(online)}</p>")
    # Article pages live at pmid/<n>, so topic links need to climb one level.
    return page(p["title"], "".join(body).replace("href='topic/", "href='../topic/"))


def slug(topic):
    return "".join(c if c.isalnum() else "-" for c in topic.lower()).strip("-")


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    work = sys.argv[1]
    cache = os.path.join(work, "cache")
    pages = os.path.join(work, "pages")
    os.makedirs(cache, exist_ok=True)
    os.makedirs(pages, exist_ok=True)

    # The ID list is saved once, so batches stay the same if PubMed grows between runs.
    # A changed QUERY gets a new key and starts afresh.
    key = hashlib.sha1(QUERY.encode()).hexdigest()[:10]
    ids_path = os.path.join(cache, f"{key}-topics.json")
    if os.path.exists(ids_path):
        with open(ids_path) as f:
            topic_ids = json.load(f)
    else:
        topic_ids = {}
        for heading, name, explode in TOPIC_LIST:
            topic_ids[name] = search_ids(topic_query(heading, explode), 1900, date.today().year)
            print(f"  {name}: {len(topic_ids[name])}", flush=True)
        with open(ids_path, "w") as f:
            json.dump(topic_ids, f)
    ids = sorted({i for found in topic_ids.values() for i in found}, key=int)
    topics_of = {}
    for name, found in topic_ids.items():
        for i in found:
            topics_of.setdefault(i, []).append(name)
    print(f"PubMed: {len(ids)} guidelines and systematic reviews")
    for start in range(0, len(ids), BATCH):
        path = os.path.join(cache, f"{key}-{start:06d}.xml")
        if os.path.exists(path):
            try:
                ET.parse(path)
                continue
            except ET.ParseError:
                print(f"  refetching damaged {os.path.basename(path)}")
        for attempt in range(4):
            data = get("efetch.fcgi", db="pubmed", id=",".join(ids[start:start + BATCH]), retmode="xml")
            try:
                ET.fromstring(data)  # NCBI sometimes ends a response early
                break
            except ET.ParseError:
                print(f"  incomplete response, retrying ({attempt + 1})", file=sys.stderr)
                time.sleep(2 ** attempt)
        else:
            raise SystemExit("error: NCBI kept sending incomplete data; run again later to resume")
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
        print(f"  fetched {min(start + BATCH, len(ids))} / {len(ids)}", flush=True)

    papers = {}
    for name in sorted(os.listdir(cache)):
        if name.startswith(key + "-0") and name.endswith(".xml"):
            for article in ET.parse(os.path.join(cache, name)).getroot().findall("PubmedArticle"):
                p = parse(article)
                p["topics"] = sorted(topics_of.get(p["pmid"], []))
                if p["sections"] and not p["withdrawn"]:
                    papers[p["pmid"]] = p

    items = []
    by_topic = {t: [] for t in TOPICS.values()}
    for p in papers.values():
        with open(os.path.join(pages, f"{p['pmid']}.html"), "w") as f:
            f.write(article_html(p))
        items.append({"path": f"pmid/{p['pmid']}", "title": p["title"], "file": f"pages/{p['pmid']}.html",
                      "mime": "text/html", "article": True})
        for t in p["topics"]:
            by_topic[t].append(p)

    e = html.escape
    for t, ps in by_topic.items():
        ps.sort(key=lambda p: (p["kind"] != "Guideline", -int(p["year"] or 0)))
        rows = "".join(f"<li><a href='../pmid/{p['pmid']}'>{e(p['title'])}</a> "
                       f"<span class='cite'>{e(p['kind'])}, {e(p['year'])}</span></li>" for p in ps)
        with open(os.path.join(pages, f"topic-{slug(t)}.html"), "w") as f:
            f.write(page(t, f"<p><a href='../index'>All topics</a></p><h1>{e(t)}</h1>"
                            f"<p class='cite'>{len(ps)} papers, guidelines first, newest first.</p><ul class='list'>{rows}</ul>"))
        items.append({"path": f"topic/{slug(t)}", "title": f"Topic: {t}", "file": f"pages/topic-{slug(t)}.html",
                      "mime": "text/html", "article": False})

    topics = "".join(f"<li><a href='topic/{slug(t)}'>{e(t)}</a> <span class='cite'>{len(ps)}</span></li>"
                     for t, ps in sorted(by_topic.items()) if ps)
    with open(os.path.join(pages, "index.html"), "w") as f:
        f.write(page("PubMed: Survival Medicine",
                     f"<h1>PubMed: Survival Medicine</h1>"
                     f"<p>{len(papers):,} guideline and systematic-review abstracts from PubMed on emergency, "
                     f"wilderness and disaster medicine, saved {date.today():%B %Y}. Search them from the "
                     f"book's search box or with Ask.</p>"
                     f"<p class='note'>These are research summaries written for clinicians. In an emergency, "
                     f"follow the app's first-aid guides first.</p>"
                     f"<h2>Topics</h2><ul class='list'>{topics}</ul>"
                     f"<p class='cite'>Source: PubMed, U.S. National Library of Medicine. Abstracts may be "
                     f"copyrighted by their publishers; this pack is for personal use.</p>"))
    items.append({"path": "index", "title": "PubMed: Survival Medicine", "file": "pages/index.html",
                  "mime": "text/html", "article": False})

    manifest = {
        "metadata": {
            "Title": "PubMed: Survival Medicine",
            "Description": "Guideline and systematic-review abstracts on emergency, wilderness and disaster medicine",
            "Language": "eng",
            "Creator": "U.S. National Library of Medicine",
            "Publisher": "Pocket Nomad",
            "Date": date.today().isoformat(),
            "Name": "pocketnomad_en_pubmed-survival",
            "Tags": "medicine;pubmed;_pictures:no",
        },
        "main": "index",
        "items": items,
    }
    icon = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pubmed-icon.png")
    if os.path.exists(icon):
        manifest["metadata"]["Illustration_48x48@1"] = os.path.relpath(icon, work)
    with open(os.path.join(work, "manifest.json"), "w") as f:
        json.dump(manifest, f)
    print(f"{len(papers)} papers with abstracts, {sum(1 for ps in by_topic.values() if ps)} topics")


if __name__ == "__main__":
    main()
