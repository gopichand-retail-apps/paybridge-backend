#!/bin/sh
set -eu

find src -type f -name '*.java' -print0 |
  xargs -0 awk '
    length($0) > 120 {
      printf "%s:%d: line length %d exceeds 120\n", FILENAME, FNR, length($0)
      failed = 1
    }
    END { exit failed }
  '
