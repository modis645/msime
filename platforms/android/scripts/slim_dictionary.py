#!/usr/bin/env python3
"""按词表白名单裁 msime-pinyin.db，并同步锁文件里的体积与 SHA-256。

用法：slim_dictionary.py <resource_dir> <words.txt> [--drop <name> ...] [--lock <lock.json>]

规则：
  * 多字词条只在白名单里才保留；单字条目一律保留（整句解码以单字为单位，缺字是打不出字）。
  * 含非汉字字符的词条一律保留（符号、字母混排不在这张表的裁剪范围内）。
  * 裁完 VACUUM，让文件真正缩小；同时按 --drop 删除不再随包的产物（如 msime-trigram.bin）。
  * 给了 --lock 就重写该锁里对应产物的 size / sha256，并删掉 --drop 掉的条目，让
    verify_resources 与 host-api 内嵌的锁与实包一致。
"""

import hashlib
import json
import os
import sqlite3
import sys

CJK = ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0x20000, 0x2FA1F))


def is_cjk(text):
    return all(any(lo <= ord(ch) <= hi for lo, hi in CJK) for ch in text)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prune(db_path, words):
    allowed = set(words)
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA journal_mode=DELETE")
    tables = [row[0] for row in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'tbl_%'")]
    total_before = total_after = 0
    kept, dropped = [], []
    for table in tables:
        before = con.execute('SELECT count(*) FROM "%s"' % table).fetchone()[0]
        # 多字、纯汉字、且不在白名单里才删；单字与非汉字条目一律保留。
        rows = con.execute('SELECT rowid, value FROM "%s"' % table).fetchall()
        doomed = [(rid,) for rid, value in rows
                  if value and len(value) > 1 and is_cjk(value) and value not in allowed]
        if doomed:
            con.executemany('DELETE FROM "%s" WHERE rowid=?' % table, doomed)
        after = con.execute('SELECT count(*) FROM "%s"' % table).fetchone()[0]
        total_before += before
        total_after += after
        if before != after:
            kept.append((table, after, before - after))
            dropped.append(before - after)
    con.commit()
    con.execute("VACUUM")
    con.close()
    return total_before, total_after, kept


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    resource_dir = sys.argv[1]
    words_path = sys.argv[2]
    drops, lock_path = [], None
    rest = sys.argv[3:]
    index = 0
    while index < len(rest):
        if rest[index] == "--drop":
            drops.append(rest[index + 1])
            index += 2
        elif rest[index] == "--lock":
            lock_path = rest[index + 1]
            index += 2
        else:
            sys.exit("unknown argument: " + rest[index])

    words = []
    with open(words_path, encoding="utf-8") as handle:
        for line in handle:
            word = line.strip()
            if word and not word.startswith("#"):
                words.append(word)

    db_path = os.path.join(resource_dir, "msime-pinyin.db")
    before_bytes = os.path.getsize(db_path)
    total_before, total_after, changed = prune(db_path, words)
    after_bytes = os.path.getsize(db_path)
    print("词条 %d -> %d（删除 %d，%.1f%%）"
          % (total_before, total_after, total_before - total_after,
             100.0 * (total_before - total_after) / max(total_before, 1)))
    print("msime-pinyin.db %d -> %d B（%.1f MiB -> %.1f MiB）"
          % (before_bytes, after_bytes, before_bytes / 2**20, after_bytes / 2**20))
    for table, left, removed in sorted(changed, key=lambda item: -item[2])[:6]:
        print("  %-14s 剩 %8d 删 %8d" % (table, left, removed))

    dropped_bytes = 0
    for name in drops:
        path = os.path.join(resource_dir, name)
        if os.path.exists(path):
            dropped_bytes += os.path.getsize(path)
            os.remove(path)
            print("已移除 %s（%.2f MiB）" % (name, dropped_bytes / 2**20))
        else:
            print("跳过 %s（不存在）" % name)
    print("本资源目录共减 %.2f MiB" % ((before_bytes - after_bytes + dropped_bytes) / 2**20))

    if lock_path:
        with open(lock_path, encoding="utf-8") as handle:
            lock = json.load(handle)
        kept = []
        for artifact in lock.get("artifacts", []):
            if artifact["name"] in drops:
                print("锁里删除条目 %s" % artifact["name"])
                continue
            path = os.path.join(resource_dir, artifact["name"])
            if os.path.exists(path):
                artifact["size"] = os.path.getsize(path)
                artifact["sha256"] = sha256_of(path)
            kept.append(artifact)
        lock["artifacts"] = kept
        with open(lock_path, "w", encoding="utf-8") as handle:
            json.dump(lock, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        print("已重写锁 %s（%d 项）" % (lock_path, len(kept)))


if __name__ == "__main__":
    main()
