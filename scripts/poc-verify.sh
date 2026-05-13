#!/usr/bin/env bash
# 兼容旧入口：仅跑核心回归 A+B（等价：./scripts/poc-tech-selection-verify.sh --quick）
exec "$(cd "$(dirname "$0")" && pwd)/poc-tech-selection-verify.sh" --quick
