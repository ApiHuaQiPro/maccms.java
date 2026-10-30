"""把 target/perf/ 下 SearchPerf 的原始输出汇总成报告用的 Markdown 表（MACC-19）。

用法（仓库根目录下）：python src/test/perf/summarize.py target/perf > target/perf/summary.md
只读原始文件，不连接数据库。
"""
import re
import sys
from pathlib import Path

SCALES = [("s100k", "10 万"), ("s500k", "50 万"), ("s1m", "100 万")]


def read_tsv(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    head = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        if not line.strip() or line.startswith("---"):
            break
        rows.append(dict(zip(head, line.split("\t"))))
    return rows


def stat(s, key):
    m = re.search(key + r"=([0-9.]+)", s)
    return float(m.group(1)) if m else None


def fmt(v):
    return "—" if v is None else f"{v:.0f}"


def latency_tables(d):
    data = {label: read_tsv(d / f"{label}-bench.tsv") for label, _ in SCALES if (d / f"{label}-bench.tsv").exists()}
    out = []
    for sort in ("time", "hits"):
        out.append(f"\n#### 第 1 页，`sort={sort}&order=desc`（ms；DB = 直连执行 count+page，HTTP = 经应用的完整请求；热态）\n")
        hdr = "| 形态 | " + " | ".join(f"{name} 命中 | {name} DB p50/p95 | {name} HTTP p50/p95" for label, name in SCALES
                                     if label in data) + " |"
        out.append(hdr)
        out.append("|" + "---|" * (1 + 3 * len(data)))
        shapes = [r["shape"] for r in data[next(iter(data))] if r["sort"] == sort and r["page"] == "1"]
        for shape in shapes:
            cells = [f"`{shape}`"]
            for label, _ in SCALES:
                if label not in data:
                    continue
                r = next(x for x in data[label] if x["shape"] == shape and x["sort"] == sort and x["page"] == "1")
                err = " ⚠" + r["cold_error"] if r["cold_error"] else ""
                herr = "" if r["hot_errors"] == "0" else f" ⚠{r['hot_errors']}错"
                cells.append(r["total"])
                cells.append(f"{fmt(stat(r['hot_total'], 'p50'))}/{fmt(stat(r['hot_total'], 'p95'))}{err}{herr}")
                codes = "" if r["http_status"] in ("{200=5}", "{200=10}", "{200=30}") else " " + r["http_status"]
                cells.append(f"{fmt(stat(r['http'], 'p50'))}/{fmt(stat(r['http'], 'p95'))}{codes}")
            out.append("| " + " | ".join(cells) + " |")
    out.append("\n#### 深分页（第 50 / 1000 页，热态 DB count+page p50/p95，ms；total ≤ 偏移量时应用不执行分页语句）\n")
    out.append("| 形态 | 排序 | " + " | ".join(f"{name} p50 | {name} p1000" for label, name in SCALES if label in data) + " |")
    out.append("|" + "---|" * (2 + 2 * len(data)))
    for shape in ("base", "area", "kw-high", "classes"):
        for sort in ("time", "hits"):
            cells = [f"`{shape}`", sort]
            for label, _ in SCALES:
                if label not in data:
                    continue
                for page in ("50", "1000"):
                    r = next(x for x in data[label] if x["shape"] == shape and x["sort"] == sort and x["page"] == page)
                    cells.append(f"{fmt(stat(r['hot_total'], 'p50'))}/{fmt(stat(r['hot_total'], 'p95'))}")
            out.append("| " + " | ".join(cells) + " |")
    out.append("\n#### 冷态（`FLUSH TABLES <库>.mac_vod` 后首次，count / page，ms）与 100 万行热态单条语句 p95\n")
    out.append("| 形态 | " + " | ".join(f"{name} 冷 count/page" for label, name in SCALES if label in data)
               + " | 100 万 热 count p95 | 100 万 热 page p95 |")
    out.append("|" + "---|" * (3 + len(data)))
    for shape in shapes:
        cells = [f"`{shape}`"]
        for label, _ in SCALES:
            if label not in data:
                continue
            r = next(x for x in data[label] if x["shape"] == shape and x["sort"] == "time" and x["page"] == "1")
            cells.append(f"{float(r['cold_count_ms']):.0f}/{float(r['cold_page_ms']):.0f}")
        if "s1m" in data:
            r = next(x for x in data["s1m"] if x["shape"] == shape and x["sort"] == "time" and x["page"] == "1")
            cells.append(fmt(stat(r["hot_count"], "p95")))
            cells.append(fmt(stat(r["hot_page"], "p95")))
        out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out)


def handler_table(d, label):
    rows = read_tsv(d / f"{label}-bench.tsv")
    out = [f"\n#### {label}：每次请求（count+page）的会话计数增量（热态平均，`sort=time`，第 1 页）\n",
           "| 形态 | 计数增量 |", "|---|---|"]
    for r in rows:
        if r["sort"] == "time" and r["page"] == "1":
            out.append(f"| `{r['shape']}` | {r['hot_session_delta_per_run']} |")
    return "\n".join(out)


def lock_table(d):
    out = ["\n#### 表锁实验（UPDATE 固定 5 次/秒，每档约 30 s；读者为低频关键字 `kw-low`，`sort=time` 第 1 页）\n",
           "| 规模 | 方式 | 读者 | UPDATE 次数 | UPDATE 延迟 p50/p95/max (ms) | 读完成数 | 读耗时 p50/p95/max (ms) | 读结果 | "
           "Table_locks_waited | 等锁会话 max/avg | 应用 CPU (ms) |",
           "|" + "---|" * 11]
    for label, name in SCALES:
        p = d / f"{label}-lock.tsv"
        if not p.exists():
            continue
        for r in read_tsv(p):
            out.append("| " + " | ".join([
                name, r["mode"], r["readers"], r["updates"],
                f"{fmt(stat(r['update_latency_ms'], 'p50'))}/{fmt(stat(r['update_latency_ms'], 'p95'))}/"
                f"{fmt(stat(r['update_latency_ms'], 'max'))}",
                r["reads_done"],
                f"{fmt(stat(r['read_latency_ms'], 'p50'))}/{fmt(stat(r['read_latency_ms'], 'p95'))}/"
                f"{fmt(stat(r['read_latency_ms'], 'max'))}",
                r["read_status"].replace("=", ":"), r["table_locks_waited"],
                f"{r['max_waiting_lock']}/{r['avg_waiting_lock']}", r["app_cpu_ms"]]) + " |")
    return "\n".join(out)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    d = Path(sys.argv[1])
    print(latency_tables(d))
    for label, _ in SCALES:
        if (d / f"{label}-bench.tsv").exists():
            print(handler_table(d, label))
    print(lock_table(d))


if __name__ == "__main__":
    main()
