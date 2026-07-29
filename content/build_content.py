#!/usr/bin/env python3
"""
韦编《论语译注》— 内容构建管线 (content build pipeline)

从本机既有的 Cloudflare 项目源数据构建一份可版本化、可增量下发的内容包。
数据来源全部为本地既有资产，不手工重建正文：

  1. /Users/ylsuen/CF/lunyu/data/dialogues.json
     杨伯峻《论语译注》全文（原文 + 译文 + 注释），541 行 → 去重 512 章。
  2. /Users/ylsuen/CF/lunyu-battle/src/data/{concepts,figures}.ts
     17 个核心概念 + 15 位人物。
  3. /Users/ylsuen/CF/lunyu-battle/src/data/bank/*.ts
     215 道人工精编题（八类题型，每个错项带 why 诊断）。
  4. /Users/ylsuen/CF/gaokao/data/all.json          (key == 'lunyu')
     /Users/ylsuen/CF/gks/data/papers/*chinese*.json (section == '《论语》经典阅读')
     北京高考《论语》经典阅读真题。

输出 dist/ 下的内容包与 manifest（含 sha256），供 Worker 下发、App 增量更新。

用法:
    python3 build_content.py            # 构建
    python3 build_content.py --check    # 只做校验，不写文件
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from collections import Counter, OrderedDict
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path

try:
    from zhconv import convert as _zh_convert
except ImportError:  # pragma: no cover
    print("需要 zhconv:  pip3 install zhconv", file=sys.stderr)
    raise

CF = Path("/Users/ylsuen/CF")
HERE = Path(__file__).resolve().parent
DIST = HERE / "dist"

SRC_DIALOGUES = CF / "lunyu" / "data" / "dialogues.json"
SRC_BATTLE = CF / "lunyu-battle" / "src"
SRC_GK_ALL = CF / "gaokao" / "data" / "all.json"
SRC_GKS_PAPERS = CF / "gks" / "data" / "papers"

SCHEMA_VERSION = 1
CONTENT_ID = "lunyu-yizhu"

# 《论语》二十篇的规范简体篇名。源数据 title 字段繁简混杂且第 12 篇之后
# 篇名缺失（退化成 "12.13" 或首位说话人），所以篇名一律由此表给出，
# 只有 x.y 编号从 title 解析。
BOOK_NAMES = {
    1: "学而", 2: "为政", 3: "八佾", 4: "里仁", 5: "公冶长",
    6: "雍也", 7: "述而", 8: "泰伯", 9: "子罕", 10: "乡党",
    11: "先进", 12: "颜渊", 13: "子路", 14: "宪问", 15: "卫灵公",
    16: "季氏", 17: "阳货", 18: "微子", 19: "子张", 20: "尧曰",
}

# 杨伯峻本每篇章数（去重后应当吻合，作为构建期断言）
EXPECTED_BOOK_COUNTS = {
    1: 16, 2: 24, 3: 26, 4: 26, 5: 28, 6: 30, 7: 38, 8: 21, 9: 31, 10: 27,
    11: 26, 12: 24, 13: 30, 14: 44, 15: 42, 16: 14, 17: 26, 18: 11, 19: 25, 20: 3,
}
EXPECTED_TOTAL = 512

MARKER_RE = re.compile(r"[⑴-⒇①-⑳]")  # ⑴-⒇ 及 ①-⑳
REF_RE = re.compile(r"(\d+)\.(\d+)")


def to_simplified(text: str) -> str:
    """繁 → 简。题库与概念表是繁体，正文已是简体；转换幂等。"""
    if not text:
        return text
    return _zh_convert(text, "zh-cn")


def sha256_of(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def canonical_json(obj) -> bytes:
    """稳定序列化：键排序 + 无多余空白，保证同样输入产出同样 sha256。"""
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


# --------------------------------------------------------------------------
# 1. 正文：原文 / 译文 / 注释
# --------------------------------------------------------------------------

def parse_annotations(raw: str) -> list[dict]:
    """
    把 "注释：⑴子——说明…\n⑵时——说明…" 拆成结构化条目。

    杨伯峻的体例是 `<marker><词目>——<释义>`，释义中可能再含换行。
    没有词目分隔符 "——" 的条目整条作为释义保留，不丢内容。
    """
    if not raw or not raw.strip():
        return []
    body = raw.strip()
    body = re.sub(r"^注释[：:]\s*", "", body)

    # 以标记符切分，保留标记符本身
    parts = re.split(r"(?=[⑴-⒇①-⑳])", body)
    out: list[dict] = []
    for part in parts:
        part = part.strip()
        if not part:
            continue
        marker = ""
        if MARKER_RE.match(part):
            marker = part[0]
            part = part[1:].strip()
        if not part:
            continue
        if "——" in part:
            term, gloss = part.split("——", 1)
        else:
            term, gloss = "", part
        out.append({
            "marker": marker,
            "index": marker_to_int(marker),
            "term": term.strip(),
            "gloss": gloss.strip(),
        })

    # 上游 dialogues.json 的注释序号有大量转录讹误（约半数章节）：
    #   · 為政 2.4 第六条本应 ⑹ 却写成 ⑷（重号且缺号）；
    #   · 公冶長 5.7 首条整个漏掉 ⑴；
    #   · 雍也 6.5 第三条写成 ⑵。
    # 但注释在原书中始终按出现次序排列，这一点是可靠的。因此把序号「修复」
    # 成严格递增：解析出的序号只有在大于前一条时才采信，否则按 前一条+1 补。
    # 原始 marker/index 一并保留，不改动原始资料，只增加可信的 number 供 App 对齐。
    #
    # 注意不能简单地用「第几条」当序号：杨伯峻常在首条注释一个正文未加标记的
    # 词（如 學而 1.1 的 ⑴「子」），此时首条对应的是 ⑴ 而正文从 ⑵ 起，
    # 按位置对齐会整体错位一格。
    previous = 0
    for entry in out:
        parsed = entry["index"]
        number = parsed if parsed > previous else previous + 1
        entry["number"] = number
        previous = number
    return out


def marker_to_int(marker: str) -> int:
    """⑴ → 1。非标记返回 0。"""
    if not marker:
        return 0
    try:
        value = unicodedata.digit(marker)
        return int(value)
    except (TypeError, ValueError):
        pass
    code = ord(marker)
    if 0x2474 <= code <= 0x2487:   # ⑴..⒇
        return code - 0x2474 + 1
    if 0x2460 <= code <= 0x2473:   # ①..⑳
        return code - 0x2460 + 1
    return 0


def strip_translation_prefix(raw: str) -> str:
    return re.sub(r"^译文[：:]\s*", "", (raw or "").strip())


def text_key(text: str) -> str:
    """去掉注释标记与空白后的正文，用作重复章判定键。"""
    return MARKER_RE.sub("", text or "").replace(" ", "").replace("\n", "").strip()


def build_chapters() -> tuple[list[dict], dict[int, int]]:
    raw = json.loads(SRC_DIALOGUES.read_text(encoding="utf-8"))

    entries = []
    for item in raw:
        match = REF_RE.search(item["title"])
        if not match:
            raise ValueError(f"条目 {item['id']} 的 title 无 x.y 编号: {item['title']!r}")
        book = int(match.group(1))
        index = int(match.group(2))
        entries.append({
            "sourceId": int(item["id"]),
            "book": book,
            "index": index,
            "rawText": item.get("text", ""),
            "rawTranslation": item.get("translation", ""),
            "rawAnnotations": item.get("annotations", ""),
        })
    entries.sort(key=lambda e: e["sourceId"])

    # 去重：source 有 29 条重复（乡党 10.25–10.27、先进 11.1–11.26 各出现两次），
    # 512 才是杨伯峻本的定数。重复条目折叠为别名而非删除，
    # 这样旧站（kz / ly）已保存的进度 id 仍可解析。
    canonical_by_key: dict[str, int] = {}
    alias_to_canonical: dict[int, int] = {}
    for entry in entries:
        key = text_key(entry["rawText"])
        first = canonical_by_key.get(key)
        if first is None:
            canonical_by_key[key] = entry["sourceId"]
        else:
            alias_to_canonical[entry["sourceId"]] = first

    chapters: list[dict] = []
    for entry in entries:
        if entry["sourceId"] in alias_to_canonical:
            continue
        book = entry["book"]
        original = entry["rawText"].strip()
        annotations = parse_annotations(entry["rawAnnotations"])
        aliases = sorted(a for a, c in alias_to_canonical.items() if c == entry["sourceId"])
        markers_in_text = [marker_to_int(m) for m in MARKER_RE.findall(original)]
        annotation_numbers = {a["number"] for a in annotations}
        # 正文里每个标记都能找到对应注释 = 点注释交互在该章完全可用
        unresolved = sorted(set(markers_in_text) - annotation_numbers)
        marker_sequence_ok = not unresolved
        chapters.append({
            "id": entry["sourceId"],
            "markersInText": markers_in_text,
            "markerSequenceOk": marker_sequence_ok,
            "unresolvedMarkers": unresolved,
            "ref": f"{book}.{entry['index']}",
            "book": book,
            "bookName": BOOK_NAMES[book],
            "index": entry["index"],
            "title": f"{BOOK_NAMES[book]} {book}.{entry['index']}",
            "original": original,
            "plainOriginal": MARKER_RE.sub("", original),
            "translation": strip_translation_prefix(entry["rawTranslation"]),
            "annotations": annotations,
            "annotationCount": len(annotations),
            "charCount": len(MARKER_RE.sub("", original)),
            "aliases": aliases,
        })

    chapters.sort(key=lambda c: (c["book"], c["index"]))
    return chapters, alias_to_canonical


# --------------------------------------------------------------------------
# 2. 概念 / 人物 / 题库（从 TypeScript 源提取）
# --------------------------------------------------------------------------

def extract_ts_array(path: Path, export_name: str) -> str:
    """从 TS 源里取出 `export const NAME: T[] = [ ... ];` 的数组字面量。"""
    src = path.read_text(encoding="utf-8")
    anchor = re.search(rf"export const {re.escape(export_name)}[^=]*=\s*\[", src)
    if not anchor:
        raise ValueError(f"{path.name} 中找不到 export {export_name}")
    start = anchor.end() - 1
    depth = 0
    in_string: str | None = None
    escaped = False
    for i in range(start, len(src)):
        ch = src[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == in_string:
                in_string = None
            continue
        if ch in "\"'`":
            in_string = ch
            continue
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    raise ValueError(f"{path.name} 中 {export_name} 数组未闭合")


def ts_literal_to_json(literal: str):
    """
    把 TS 对象字面量转成 JSON。题库源里只用到裸键、单双引号字符串、
    数组、数字、布尔与尾逗号——不需要完整 JS 解析器。
    """
    out: list[str] = []
    i = 0
    n = len(literal)
    while i < n:
        ch = literal[i]
        # 注释
        if ch == "/" and i + 1 < n and literal[i + 1] == "/":
            while i < n and literal[i] != "\n":
                i += 1
            continue
        if ch == "/" and i + 1 < n and literal[i + 1] == "*":
            end = literal.find("*/", i + 2)
            i = (end + 2) if end != -1 else n
            continue
        # 字符串：统一成 JSON 双引号串
        if ch in "\"'`":
            quote = ch
            i += 1
            buf: list[str] = []
            while i < n:
                c = literal[i]
                if c == "\\":
                    nxt = literal[i + 1] if i + 1 < n else ""
                    if nxt == quote:
                        buf.append(quote)
                    elif nxt == "\n":
                        pass
                    else:
                        buf.append(c)
                        buf.append(nxt)
                    i += 2
                    continue
                if c == quote:
                    i += 1
                    break
                buf.append(c)
                i += 1
            out.append(json.dumps("".join(buf), ensure_ascii=False))
            continue
        # 裸键 → 加引号
        if ch.isalpha() or ch == "_":
            j = i
            while j < n and (literal[j].isalnum() or literal[j] in "_$"):
                j += 1
            word = literal[i:j]
            k = j
            while k < n and literal[k] in " \t\n\r":
                k += 1
            if k < n and literal[k] == ":" and word not in ("true", "false", "null"):
                out.append(json.dumps(word))
            else:
                out.append(word)
            i = j
            continue
        out.append(ch)
        i += 1

    text = "".join(out)
    text = re.sub(r",(\s*[}\]])", r"\1", text)  # 尾逗号
    return json.loads(text)


def build_concepts() -> list[dict]:
    path = SRC_BATTLE / "data" / "concepts.ts"
    items = ts_literal_to_json(extract_ts_array(path, "CONCEPTS"))
    out = []
    for c in items:
        out.append({
            "id": c["id"],
            "name": to_simplified(c.get("name", "")),
            "pinyin": c.get("pinyin", ""),
            "gloss": to_simplified(c.get("gloss", "")),
            "detail": to_simplified(c.get("detail", "")) if c.get("detail") else "",
            "refs": c.get("refs", []),
        })
    return out


def build_figures() -> list[dict]:
    path = SRC_BATTLE / "data" / "figures.ts"
    items = ts_literal_to_json(extract_ts_array(path, "FIGURES"))
    out = []
    for f in items:
        out.append({
            "id": f["id"],
            "name": to_simplified(f.get("name", "")),
            "style": to_simplified(f.get("style", "")) if f.get("style") else "",
            "role": to_simplified(f.get("role", "")) if f.get("role") else "",
            "gloss": to_simplified(f.get("gloss", "")),
            "refs": f.get("refs", []),
        })
    return out


BANK_FILES = [
    ("comprehension", "comprehension.ts", "COMPREHENSION_ITEMS"),
    ("interpretation", "interpretation.ts", "INTERPRETATION_ITEMS"),
    ("concept", "concept.ts", "CONCEPT_ITEMS"),
    ("linkage", "linkage.ts", "LINKAGE_ITEMS"),
    ("conflict", "conflict.ts", "CONFLICT_ITEMS"),
    ("figure", "figure.ts", "FIGURE_ITEMS"),
    ("application", "application.ts", "APPLICATION_ITEMS"),
    ("misread", "misread.ts", "MISREAD_ITEMS"),
]


def build_bank(alias_map: dict[int, int]) -> list[dict]:
    out: list[dict] = []
    for _kind, filename, export_name in BANK_FILES:
        path = SRC_BATTLE / "data" / "bank" / filename
        items = ts_literal_to_json(extract_ts_array(path, export_name))
        for q in items:
            refs = [alias_map.get(r, r) for r in q.get("refs", [])]
            options = [{
                "id": o["id"],
                "text": to_simplified(o.get("text", "")),
                "why": to_simplified(o.get("why", "")) if o.get("why") else "",
            } for o in q.get("options", [])]
            out.append({
                "id": q["id"],
                "type": q["type"],
                "refs": refs,
                "concepts": q.get("concepts", []),
                "figures": q.get("figures", []),
                "difficulty": q.get("difficulty", 1),
                "cognitive": q.get("cognitive", "understand"),
                "purpose": to_simplified(q.get("purpose", "")),
                "stem": to_simplified(q.get("stem", "")),
                "context": to_simplified(q.get("context", "")) if q.get("context") else "",
                "options": options,
                "answerId": q["answerId"],
                "explanation": to_simplified(q.get("explanation", "")),
            })
    return out


# --------------------------------------------------------------------------
# 3. 高考真题
# --------------------------------------------------------------------------

def norm_for_match(text: str) -> str:
    """归一化后用于把真题材料匹配回具体章句。"""
    text = to_simplified(text or "")
    return re.sub(r"[^一-鿿]", "", text)


def map_passages(material: str, chapters: list[dict], limit: int = 6) -> list[int]:
    """
    把真题材料匹配回《论语》章句。

    不能只做「章句 ⊆ 材料」的单向包含判定：高考材料有三种形态——
      · 整章引用（阳货 17.8 六言六蔽）——双向都成立；
      · 只截取长章的一段（2015 侍坐篇只引篇末对话）——章句不在材料里；
      · 转录有讹字（2019 材料作「贫与残」，当作「贫与贱」）——完全不含。
    所以改用最长公共子串：只要两边有足够长的连续重合就算命中，
    并按重合长度排序，长章优先。
    """
    hay = norm_for_match(material)
    if len(hay) < 8:
        return []

    hits: list[tuple[int, int]] = []
    for ch in chapters:
        needle = norm_for_match(ch["plainOriginal"])
        if len(needle) < 8:
            continue
        matcher = SequenceMatcher(None, needle, hay, autojunk=False)
        block = matcher.find_longest_match(0, len(needle), 0, len(hay))
        overlap = block.size
        # 两条命中规则，取其一即可：
        #  · 重合覆盖了该章一半以上——短章的正常引用；
        #  · 连续重合 ≥25 字——长章只被节引时（2015 侍坐篇只引篇末对话，
        #    材料其余部分是题干散文，占比法会把它判掉），
        #    但连续二十五字与某章逐字相同，不可能是巧合。
        # 12 字绝对下限挡住「子曰」「君子」这类通用短语。
        if overlap >= 12 and (overlap >= 0.5 * len(needle) or overlap >= 25):
            hits.append((overlap, ch["id"]))

    hits.sort(key=lambda h: (-h[0], h[1]))
    return [cid for _, cid in hits[:limit]]


def build_gaokao(chapters: list[dict]) -> list[dict]:
    out: list[dict] = []
    seen: set[str] = set()

    # 3a. gk.bdfz.net 题库（gaokao/data/all.json），key == 'lunyu'
    if SRC_GK_ALL.exists():
        bank = json.loads(SRC_GK_ALL.read_text(encoding="utf-8"))
        for item in bank:
            if item.get("key") != "lunyu":
                continue
            materials = [m.get("text", "") for m in (item.get("materials") or []) if m.get("text")]
            if not materials:
                materials = [item.get(f"material{i}") or "" for i in range(1, 4)]
            material = "\n\n".join(t for t in materials if t)
            questions = []
            for q in (item.get("questions") or []):
                if not q.get("text"):
                    continue
                questions.append({
                    "id": q.get("id") or f"q{q.get('qIndex', len(questions) + 1)}",
                    "prompt": to_simplified(q["text"]),
                    "score": q.get("score"),
                })
            answers = item.get("ai_answers") or {}
            current = item.get("ai_answer_current_version")
            reference = item.get("reference_answer") or item.get("_legacyReferenceAnswer") or ""
            model_answer = ""
            if isinstance(answers, dict):
                picked = answers.get(current) if current else None
                if picked is None and answers:
                    picked = next(iter(answers.values()))
                if isinstance(picked, str):
                    model_answer = picked
                elif isinstance(picked, dict):
                    model_answer = picked.get("text") or json.dumps(picked, ensure_ascii=False)
            entry_id = f"gk-{item.get('year')}-{item.get('key')}"
            if entry_id in seen:
                continue
            seen.add(entry_id)
            out.append({
                "id": entry_id,
                "year": item.get("year"),
                "province": "北京",
                "source": "gk.bdfz.net 北京高考语文真题库",
                "sectionLabel": "《论语》经典阅读",
                "topic": to_simplified(item.get("topic") or ""),
                "material": to_simplified(material),
                "questions": questions,
                "referenceAnswer": to_simplified(reference) if reference else "",
                "modelAnswer": to_simplified(model_answer) if model_answer else "",
                "answerSource": current or ("official" if reference else ""),
                "passages": map_passages(material, chapters),
            })

    # 3a-bis. 微写作里以《论语》命题的年份（如 2018 为孔门弟子写评语）。
    # 这类题不在「经典阅读」大题下，但确是《论语》考点，纳入才算收全。
    if SRC_GK_ALL.exists():
        bank = json.loads(SRC_GK_ALL.read_text(encoding="utf-8"))
        for item in bank:
            if item.get("key") != "weixiezuo":
                continue
            prompts = []
            for q in (item.get("questions") or []):
                text = q.get("text") or ""
                if "论语" in text:
                    prompts.append({
                        "id": q.get("id") or f"q{q.get('qIndex', len(prompts) + 1)}",
                        "prompt": to_simplified(text),
                        "score": q.get("score"),
                    })
            if not prompts:
                continue
            entry_id = f"gk-{item.get('year')}-weixiezuo-lunyu"
            if entry_id in seen:
                continue
            seen.add(entry_id)
            out.append({
                "id": entry_id,
                "year": item.get("year"),
                "province": "北京",
                "source": "gk.bdfz.net 北京高考语文真题库",
                "sectionLabel": "微写作（《论语》命题）",
                "topic": to_simplified(item.get("topic") or ""),
                "material": "",
                "questions": prompts,
                "referenceAnswer": "",
                "modelAnswer": "",
                "answerSource": "",
                "passages": [],
            })

    # 3b. gks 试卷（section == '《论语》经典阅读'），按年份聚合成一组
    if SRC_GKS_PAPERS.exists():
        by_paper: "OrderedDict[str, dict]" = OrderedDict()
        for path in sorted(SRC_GKS_PAPERS.glob("*chinese*.json")):
            try:
                paper = json.loads(path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                continue
            for q in paper.get("questions", []):
                if q.get("section") != "《论语》经典阅读":
                    continue
                pid = f"gks-{paper.get('year')}-{paper.get('id')}"
                bucket = by_paper.setdefault(pid, {
                    "id": pid,
                    "year": paper.get("year"),
                    "province": "北京",
                    "source": paper.get("source_name") or "gks.bdfz.net",
                    "sectionLabel": "《论语》经典阅读",
                    "topic": "",
                    "material": "",
                    "questions": [],
                    "referenceAnswer": "",
                    "modelAnswer": "",
                    "answerSource": (paper.get("meta") or {}).get("answer_model", ""),
                    "passages": [],
                })
                if q.get("material_text") and len(q["material_text"]) > len(bucket["material"]):
                    bucket["material"] = to_simplified(q["material_text"])
                bucket["questions"].append({
                    "id": f"q{q.get('q_number') or len(bucket['questions']) + 1}",
                    "prompt": to_simplified(q.get("stem", "")),
                    "score": q.get("score"),
                    "answer": to_simplified(q.get("answer") or ""),
                    "explanation": to_simplified(q.get("explanation") or ""),
                    "knowledgePoints": [to_simplified(k) for k in (q.get("knowledge_points") or [])],
                    "difficulty": q.get("difficulty"),
                })
        for pid, bucket in by_paper.items():
            # gk 库已收录同年份的「经典阅读」，就把 gks 的逐题答案并进去，不重复建组。
            # 必须同时比对 sectionLabel：同一年可能既有经典阅读又有微写作命题。
            twin = next(
                (e for e in out
                 if e["year"] == bucket["year"] and e["sectionLabel"] == bucket["sectionLabel"]),
                None,
            )
            if twin:
                have = {q["id"] for q in twin["questions"]}
                for q in bucket["questions"]:
                    if q["id"] not in have:
                        twin["questions"].append(q)
                    else:
                        for existing in twin["questions"]:
                            if existing["id"] == q["id"]:
                                existing.setdefault("answer", q.get("answer", ""))
                                existing.setdefault("explanation", q.get("explanation", ""))
                                existing.setdefault("knowledgePoints", q.get("knowledgePoints", []))
                if not twin["material"] and bucket["material"]:
                    twin["material"] = bucket["material"]
                    twin["passages"] = map_passages(bucket["material"], chapters)
                continue
            bucket["passages"] = map_passages(bucket["material"], chapters)
            out.append(bucket)

    out.sort(key=lambda e: (e["year"] or 0))
    return out


# --------------------------------------------------------------------------
# 4. 组装
# --------------------------------------------------------------------------

def validate(chapters, bank, concepts, figures, gaokao) -> list[str]:
    problems: list[str] = []

    if len(chapters) != EXPECTED_TOTAL:
        problems.append(f"章数 {len(chapters)} != 期望 {EXPECTED_TOTAL}")

    counts = Counter(c["book"] for c in chapters)
    for book, expected in EXPECTED_BOOK_COUNTS.items():
        actual = counts.get(book, 0)
        if actual != expected:
            problems.append(f"第 {book} 篇《{BOOK_NAMES[book]}》 {actual} 章 != 期望 {expected}")

    for ch in chapters:
        if not ch["original"].strip():
            problems.append(f"{ch['title']} 原文为空")
        if not ch["translation"].strip():
            problems.append(f"{ch['title']} 译文为空")

    ids = {c["id"] for c in chapters}
    for q in bank:
        for ref in q["refs"]:
            if ref not in ids:
                problems.append(f"题 {q['id']} 指向不存在的章 {ref}")
        if q["answerId"] not in {o["id"] for o in q["options"]}:
            problems.append(f"题 {q['id']} 的 answerId 不在选项中")
        for o in q["options"]:
            if o["id"] != q["answerId"] and not o["why"]:
                problems.append(f"题 {q['id']} 的错项 {o['id']} 缺少 why 诊断")

    concept_ids = {c["id"] for c in concepts}
    for q in bank:
        for c in q["concepts"]:
            if c not in concept_ids:
                problems.append(f"题 {q['id']} 引用未知概念 {c}")

    figure_ids = {f["id"] for f in figures}
    for q in bank:
        for f in q.get("figures", []):
            if f not in figure_ids:
                problems.append(f"题 {q['id']} 引用未知人物 {f}")

    for g in gaokao:
        if not g["questions"]:
            problems.append(f"高考条目 {g['id']} 无题目")

    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验不写文件")
    args = parser.parse_args()

    chapters, alias_map = build_chapters()
    concepts = build_concepts()
    figures = build_figures()
    bank = build_bank(alias_map)
    gaokao = build_gaokao(chapters)

    problems = validate(chapters, bank, concepts, figures, gaokao)
    if problems:
        print(f"校验失败（{len(problems)} 项）:", file=sys.stderr)
        for p in problems[:40]:
            print("  -", p, file=sys.stderr)
        return 1

    # 章 → 高考真题反向索引
    gaokao_by_chapter: dict[int, list[str]] = {}
    for g in gaokao:
        for cid in g["passages"]:
            gaokao_by_chapter.setdefault(cid, []).append(g["id"])
    for ch in chapters:
        ch["gaokaoIds"] = gaokao_by_chapter.get(ch["id"], [])
        ch["questionCount"] = sum(1 for q in bank if ch["id"] in q["refs"])

    books = [{
        "book": b,
        "name": BOOK_NAMES[b],
        "chapterCount": sum(1 for c in chapters if c["book"] == b),
        "charCount": sum(c["charCount"] for c in chapters if c["book"] == b),
    } for b in sorted(BOOK_NAMES)]

    payload = {
        "chapters": chapters,
        "books": books,
        "concepts": concepts,
        "figures": figures,
        "bank": bank,
        "gaokao": gaokao,
        "aliases": {str(k): v for k, v in sorted(alias_map.items())},
    }

    content_bytes = canonical_json(payload)
    content_hash = sha256_of(content_bytes)

    manifest = {
        "schema": "lunyu-content-v1",
        "schemaVersion": SCHEMA_VERSION,
        "contentId": CONTENT_ID,
        "contentVersion": content_hash[:16],
        "sha256": content_hash,
        "size": len(content_bytes),
        "deltas": [],
        "builtAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "counts": {
            "chapters": len(chapters),
            "books": len(books),
            "concepts": len(concepts),
            "figures": len(figures),
            "bank": len(bank),
            "gaokao": len(gaokao),
            "gaokaoQuestions": sum(len(g["questions"]) for g in gaokao),
            "annotations": sum(c["annotationCount"] for c in chapters),
            "aliases": len(alias_map),
        },
        "sources": [
            {"name": "杨伯峻《论语译注》全文", "path": str(SRC_DIALOGUES.relative_to(CF)), "items": 541, "deduped": len(chapters)},
            {"name": "核心概念 / 人物", "path": "lunyu-battle/src/data", "items": len(concepts) + len(figures)},
            {"name": "人工精编题库", "path": "lunyu-battle/src/data/bank", "items": len(bank)},
            {"name": "北京高考《论语》经典阅读真题", "path": "gaokao/data/all.json + gks/data/papers", "items": len(gaokao)},
        ],
    }

    print("=== 内容构建结果 ===")
    for k, v in manifest["counts"].items():
        print(f"  {k:18s} {v}")
    print(f"  contentVersion     {manifest['contentVersion']}")
    print(f"  size               {manifest['size']:,} bytes")
    mapped = sum(1 for g in gaokao if g["passages"])
    print(f"  高考条目已映射到章句  {mapped}/{len(gaokao)}")
    annotated = [c for c in chapters if c["annotations"] or c["markersInText"]]
    dangling = [c for c in annotated if not c["markerSequenceOk"]]
    print(f"  正文注释标记可解析    {len(annotated) - len(dangling)}/{len(annotated)}")
    for c in dangling:
        print(f"    ! {c['title']} 上游缺注释，悬空标记 {c['unresolvedMarkers']}")

    if args.check:
        print("\n--check：校验通过，未写文件。")
        return 0

    DIST.mkdir(parents=True, exist_ok=True)
    (DIST / "content.json").write_bytes(content_bytes)
    (DIST / "manifest.json").write_bytes(
        json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    )
    # 分片，供增量下发与 App 内按需加载
    for name, part in (
        ("chapters.json", {"chapters": chapters, "books": books, "aliases": payload["aliases"]}),
        ("bank.json", {"bank": bank, "concepts": concepts, "figures": figures}),
        ("gaokao.json", {"gaokao": gaokao}),
    ):
        (DIST / name).write_bytes(canonical_json(part))

    print(f"\n已写入 {DIST}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
