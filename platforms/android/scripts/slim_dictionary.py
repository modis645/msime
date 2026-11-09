#!/usr/bin/env python3
"""按词表白名单裁 msime-pinyin.db，并同步锁文件里的体积与 SHA-256。

用法：slim_dictionary.py <resource_dir> <words.txt>
        [--stage <dir>] [--drop <name> ...] [--lock <lock.json>]

规则：
  * 多字词条只在白名单里才保留；单字条目一律保留（整句解码以单字为单位，缺字是打不出字）。
  * 含非汉字字符的词条一律保留（符号、字母混排不在这张表的裁剪范围内）。
  * 裁完 VACUUM，让文件真正缩小；同时按 --drop 删除不再随包的产物（如 msime-trigram.bin）。
  * 给了 --lock 就重写该锁里对应产物的 size / sha256，并删掉 --drop 掉的条目，让
    verify_resources 与 host-api 内嵌的锁与实包一致。
  * 给了 --stage 就先从 <resource_dir> 复制"该版本随包交付"的那部分到 <dir>，再在 <dir> 里
    裁剪：下载目录（内容寻址的 resource generation）必须保持与完整锁逐字节一致，不能就地改，
    所以裁剪只能在副本上做。哪些文件算随包交付由锁文件减掉按需下载的两组算出来，见
    crates/client-core/src/resources.rs 的 ON_DEMAND_JAPANESE_ARTIFACTS / ON_DEMAND_WUBI_ARTIFACTS。
"""

import hashlib
import json
import os
import shutil
import sqlite3
import sys

CJK = ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0x20000, 0x2FA1F))

# 与 resources.rs 的 on_demand_artifacts("android") 一致：这两组由应用按需下载，不进 APK。
ON_DEMAND = {
    "msime-japanese.dat",
    "msime-mozc_dictionary_oss_README.txt",
    "msime-mozc_LICENSE.txt",
    "msime-wubi.db",
}


def is_cjk(text):
    return all(any(lo <= ord(ch) <= hi for lo, hi in CJK) for ch in text)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stage(resource_dir, stage_dir, lock):
    """把随包交付的文件复制到 stage_dir，返回新的工作目录。"""
    names = [artifact["name"] for artifact in lock.get("artifacts", [])
             if artifact["name"] not in ON_DEMAND]
    if not names:
        sys.exit("--stage 需要 --lock：没有锁就不知道这一版带哪些文件")
    os.makedirs(stage_dir, exist_ok=True)
    copied = 0
    for name in names:
        source = os.path.join(resource_dir, name)
        destination = os.path.join(stage_dir, name)
        if not os.path.isfile(source):
            sys.exit("资源目录里缺 %s，先跑 install_resources" % source)
        shutil.copyfile(source, destination)
        copied += os.path.getsize(destination)
    print("已暂存 %d 个随包文件到 %s（%.2f MiB）"
          % (len(names), stage_dir, copied / 2**20))
    return stage_dir


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
    drops, lock_path, stage_dir = [], None, None
    rest = sys.argv[3:]
    index = 0
    while index < len(rest):
        if rest[index] == "--drop":
            drops.append(rest[index + 1])
            index += 2
        elif rest[index] == "--lock":
            lock_path = rest[index + 1]
            index += 2
        elif rest[index] == "--stage":
            stage_dir = rest[index + 1]
            index += 2
        else:
            sys.exit("unknown argument: " + rest[index])

    lock = None
    if lock_path:
        with open(lock_path, encoding="utf-8") as handle:
            lock = json.load(handle)

    if stage_dir:
        resource_dir = stage(resource_dir, stage_dir, lock)

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

    remaining = sorted(os.listdir(resource_dir))
    print("目录现有 %d 项：%s" % (len(remaining), "、".join(remaining)))


if __name__ == "__main__":
    main()
