#!/usr/bin/env python3
"""Clinical library ingestion (spec 6.9).

Reads scripts/clinical_sources.yaml, fetches PubMed abstracts (NCBI E-utilities efetch),
chunks text to ~300-500 words on paragraph boundaries, embeds with OpenAI
text-embedding-3-small (1536 dims) and upserts into clinical_knowledge_chunk by
(source_doc_id, chunk_index). Run by hand; the app never fetches at question time.

Dependencies: pip install requests "psycopg[binary]" pyyaml   (run with python3 -I)

    python3 -I scripts/clinical_ingest.py --db "$DATABASE_URL"
    python3 -I scripts/clinical_ingest.py --dry-run --sources scripts/fixtures/clinical_sources_fixture.yaml

Environment: OPENAI_API_KEY (embeddings), optional NCBI_API_KEY / NCBI_EMAIL (polite E-utilities use).
condition_tag is the source topic in UPPER_SNAKE_CASE (never GENERAL: retrieval excludes it).
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

EMBED_MODEL = "text-embedding-3-small"
EMBED_DIMS = 1536
MIN_WORDS, MAX_WORDS = 300, 500
EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
DEFAULT_SOURCES = Path(__file__).resolve().parent / "clinical_sources.yaml"


# ---------------------------------------------------------------- chunking
def _words(s: str) -> int:
    return len(s.split())


def _split_long_paragraph(p: str) -> list[str]:
    """Split an over-long paragraph on sentence boundaries into pieces <= MAX_WORDS."""
    out, cur = [], []
    for sent in re.split(r"(?<=[.!?])\s+", p.strip()):
        if cur and _words(" ".join(cur + [sent])) > MAX_WORDS:
            out.append(" ".join(cur))
            cur = []
        cur.append(sent)
    if cur:
        out.append(" ".join(cur))
    return out


def chunk_text(text: str) -> list[str]:
    paras = []
    for p in re.split(r"\n\s*\n", (text or "").strip()):
        p = " ".join(p.split())
        if p:
            paras.extend(_split_long_paragraph(p) if _words(p) > MAX_WORDS else [p])
    chunks: list[str] = []
    cur: list[str] = []
    for p in paras:
        if cur and _words(" ".join(cur + [p])) > MAX_WORDS and _words(" ".join(cur)) >= MIN_WORDS:
            chunks.append("\n\n".join(cur))
            cur = []
        cur.append(p)
    if cur:
        tail = "\n\n".join(cur)
        if chunks and _words(tail) < MIN_WORDS // 2 and _words(chunks[-1] + " " + tail) <= MAX_WORDS + MIN_WORDS // 2:
            chunks[-1] += "\n\n" + tail  # fold a tiny trailing fragment into the previous chunk
        else:
            chunks.append(tail)
    return chunks


# ---------------------------------------------------------------- sources
def load_sources(path: Path) -> list[dict]:
    import yaml
    with open(path, encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}
    return data.get("sources") or []


def condition_tag(topic: str) -> str:
    tag = re.sub(r"[^A-Za-z0-9]+", "_", topic or "").strip("_").upper()[:64]
    return tag if tag and tag != "GENERAL" else "CLINICAL_REFERENCE"


def _ncbi_params(extra: dict) -> dict:
    p = dict(extra, tool="glucose-monitor-ingest")
    if os.environ.get("NCBI_API_KEY"):
        p["api_key"] = os.environ["NCBI_API_KEY"]
    if os.environ.get("NCBI_EMAIL"):
        p["email"] = os.environ["NCBI_EMAIL"]
    return p


def resolve_doi(doi: str) -> str | None:
    import requests
    r = requests.get(f"{EUTILS}/esearch.fcgi", timeout=30,
                     params=_ncbi_params({"db": "pubmed", "term": f"{doi}[AID]", "retmode": "json"}))
    r.raise_for_status()
    ids = r.json().get("esearchresult", {}).get("idlist", [])
    return ids[0] if len(ids) == 1 else None


def fetch_pubmed(pmid: str) -> dict | None:
    import requests
    r = requests.get(f"{EUTILS}/efetch.fcgi", timeout=30,
                     params=_ncbi_params({"db": "pubmed", "id": pmid, "retmode": "xml"}))
    r.raise_for_status()
    art = ET.fromstring(r.content).find(".//PubmedArticle")
    if art is None:
        return None
    title = "".join(art.find(".//ArticleTitle").itertext()) if art.find(".//ArticleTitle") is not None else ""
    parts = []
    for ab in art.findall(".//Abstract/AbstractText"):
        label, txt = ab.get("Label"), "".join(ab.itertext()).strip()
        parts.append(f"{label}: {txt}" if label else txt)
    year = (art.findtext(".//JournalIssue/PubDate/Year") or art.findtext(".//ArticleDate/Year")
            or (art.findtext(".//JournalIssue/PubDate/MedlineDate") or "")[:4])
    authors = art.findall(".//AuthorList/Author")
    first = ""
    if authors:
        first = f"{authors[0].findtext('LastName') or ''} {authors[0].findtext('Initials') or ''}".strip()
    return {"title": title, "abstract": "\n\n".join(parts), "year": year or None,
            "journal": art.findtext(".//Journal/ISOTitle") or art.findtext(".//Journal/Title") or "",
            "first_author": first, "n_authors": len(authors)}


def build_documents(sources: list[dict], dry_run: bool, notes: list[str]) -> list[dict]:
    """Return documents: {doc_id, title, citation, url, year, topic, source_name, source_type, evidence, text}."""
    docs = []
    for s in sources:
        sid, kind = s.get("id"), s.get("kind")
        topic = s.get("topic") or "CLINICAL_REFERENCE"
        if not sid or kind not in ("guideline", "pubmed"):
            notes.append(f"SKIP invalid source entry: {s!r}")
            continue
        if kind == "guideline":
            text = (s.get("text") or "").strip() or (s.get("summary") or "").strip()
            if not text:
                notes.append(f"SKIP {sid}: no text/summary (summary_only TODO)")
                continue
            org = s.get("org") or "Guideline"
            docs.append({"doc_id": sid, "title": s["title"], "url": s.get("url"), "year": s.get("year"),
                         "topic": topic, "source_name": org[:64], "source_type": "GUIDELINE",
                         "evidence": "GUIDELINE", "text": text,
                         "citation": s.get("citation") or f"{org}: {s['title']} ({s.get('year')})"})
            continue
        pmid, doi = s.get("pmid"), s.get("doi")
        abstract, meta = (s.get("abstract") or "").strip(), {}
        if dry_run:
            if not abstract:
                notes.append(f"NOTE {sid}: dry-run skips PubMed fetch and no inline abstract -> 0 chunks")
        else:
            if not pmid and doi:
                pmid = resolve_doi(doi)
                time.sleep(0.4)
                if pmid:
                    notes.append(f"RESOLVED {sid}: doi {doi} -> PMID {pmid} (verify, then pin in YAML)")
            if not pmid:
                notes.append(f"SKIP {sid}: pmid null (fill in clinical_sources.yaml)")
                continue
            meta = fetch_pubmed(str(pmid)) or {}
            time.sleep(0.4)
            abstract = meta.get("abstract") or abstract
            if not abstract:
                notes.append(f"SKIP {sid}: PMID {pmid} has no abstract")
                continue
        if not abstract:
            continue
        title = meta.get("title") or s.get("title") or sid
        year = meta.get("year") or s.get("year")
        who = meta.get("first_author") or s.get("first_author") or ""
        cite = s.get("citation") or (
            f"{who}{' et al.' if meta.get('n_authors', 2) > 1 else ''} {title}. {meta.get('journal', '')} {year or ''}."
            f"{' PMID ' + str(pmid) if pmid else ''}").strip()
        url = s.get("url") or (f"https://pubmed.ncbi.nlm.nih.gov/{pmid}/" if pmid else
                               (f"https://doi.org/{doi}" if doi else None))
        docs.append({"doc_id": f"pmid-{pmid}" if pmid else sid, "title": title, "url": url, "year": year,
                     "topic": topic, "source_name": "PubMed", "source_type": "PUBMED_ABSTRACT",
                     "evidence": s.get("evidence_level") or "STUDY", "text": abstract, "citation": cite})
    return docs


# ---------------------------------------------------------------- embeddings / db
def embed(texts: list[str]) -> list[list[float]]:
    import requests
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        sys.exit("OPENAI_API_KEY is not set")
    out: list[list[float]] = []
    for i in range(0, len(texts), 64):
        for attempt in range(5):
            r = requests.post("https://api.openai.com/v1/embeddings", timeout=60,
                              headers={"Authorization": f"Bearer {key}"},
                              json={"model": EMBED_MODEL, "input": texts[i:i + 64]})
            if r.status_code in (429, 500, 502, 503) and attempt < 4:
                time.sleep(2 ** attempt)
                continue
            r.raise_for_status()
            break
        data = sorted(r.json()["data"], key=lambda d: d["index"])
        for d in data:
            if len(d["embedding"]) != EMBED_DIMS:
                sys.exit(f"unexpected embedding size {len(d['embedding'])}")
            out.append(d["embedding"])
    return out


def jdbc_to_dsn(url: str) -> str:
    return url[5:] if url.startswith("jdbc:") else url


UPSERT = """
INSERT INTO clinical_knowledge_chunk
 (title, content, condition_tag, evidence_level, active, source_name, source_url, source_title,
  source_topic, source_published_at, source_type, updated_at, embedding, citation, chunk_index, source_doc_id)
VALUES (%(title)s, %(content)s, %(tag)s, %(evidence)s, TRUE, %(source_name)s, %(url)s, %(source_title)s,
  %(topic)s, %(published)s, %(source_type)s, NOW(), %(embedding)s::vector, %(citation)s, %(idx)s, %(doc_id)s)
ON CONFLICT (source_doc_id, chunk_index) DO UPDATE SET
  title=EXCLUDED.title, content=EXCLUDED.content, condition_tag=EXCLUDED.condition_tag,
  evidence_level=EXCLUDED.evidence_level, active=TRUE, source_name=EXCLUDED.source_name,
  source_url=EXCLUDED.source_url, source_title=EXCLUDED.source_title, source_topic=EXCLUDED.source_topic,
  source_published_at=EXCLUDED.source_published_at, source_type=EXCLUDED.source_type, updated_at=NOW(),
  embedding=EXCLUDED.embedding, citation=EXCLUDED.citation
"""


def ingest(dsn: str, docs: list[dict], reembed: bool) -> None:
    import psycopg
    with psycopg.connect(jdbc_to_dsn(dsn)) as conn, conn.cursor() as cur:
        for d in docs:
            chunks = chunk_text(d["text"])
            cur.execute("SELECT chunk_index, content, embedding IS NOT NULL FROM clinical_knowledge_chunk "
                        "WHERE source_doc_id=%s", (d["doc_id"],))
            existing = {r[0]: (r[1], r[2]) for r in cur.fetchall()}
            todo = [i for i, c in enumerate(chunks)
                    if reembed or i not in existing or existing[i][0] != c or not existing[i][1]]
            vecs = dict(zip(todo, embed([f"{d['title']}\n\n{chunks[i]}" for i in todo]))) if todo else {}
            for i in todo:
                n = len(chunks)
                cur.execute(UPSERT, {
                    "title": (d["title"] if n == 1 else f"{d['title']} (part {i + 1}/{n})")[:200],
                    "content": chunks[i], "tag": condition_tag(d["topic"]), "evidence": d["evidence"],
                    "source_name": d["source_name"], "url": d["url"], "source_title": d["title"][:255],
                    "topic": d["topic"][:128],
                    "published": f"{d['year']}-01-01T00:00:00Z" if d.get("year") else None,
                    "source_type": d["source_type"],
                    "embedding": "[" + ",".join(f"{x:.8f}" for x in vecs[i]) + "]",
                    "citation": d["citation"], "idx": i, "doc_id": d["doc_id"]})
            # chunks that no longer exist after a re-chunk are deactivated, never deleted
            cur.execute("UPDATE clinical_knowledge_chunk SET active=FALSE WHERE source_doc_id=%s AND chunk_index >= %s",
                        (d["doc_id"], len(chunks)))
            print(f"{d['doc_id']}: {len(chunks)} chunks, {len(todo)} upserted/embedded")
        conn.commit()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=os.environ.get("DATABASE_URL"), help="Postgres URL (or env DATABASE_URL)")
    ap.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    ap.add_argument("--reembed", action="store_true", help="re-embed and rewrite all chunks")
    ap.add_argument("--dry-run", action="store_true", help="no network, no DB: print chunk counts")
    a = ap.parse_args()

    notes: list[str] = []
    docs = build_documents(load_sources(a.sources), a.dry_run, notes)
    total = 0
    for d in docs:
        n = len(chunk_text(d["text"]))
        total += n
        print(f"{d['doc_id']}: {n} chunks ({_words(d['text'])} words) tag={condition_tag(d['topic'])}")
    for n in notes:
        print(n)
    print(f"TOTAL chunks: {total} from {len(docs)} documents" + (" (dry-run)" if a.dry_run else ""))
    if a.dry_run:
        return 0
    if not a.db:
        sys.exit("--db or DATABASE_URL required")
    ingest(a.db, docs, a.reembed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
