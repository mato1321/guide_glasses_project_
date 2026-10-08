"""
打包 AWS Lambda 的部署 zip（Python 3.12、x86_64）。

    cd backend
    .venv\\Scripts\\python deploy\\aws\\build_lambda.py

產出 backend/build/lambda.zip（build/ 不進版控）。

在 Windows 上就能打包：pip 用 --platform 直接下載 Linux 版的 wheel，不需要 Docker。
只放 app/ 與 requirements-lambda.txt 的套件 —— .env、credentials/、models/、debug/、
tests/ 一律不會進 zip，金鑰改由 deploy.py 設成 Lambda 環境變數。
"""

import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[2]
BUILD = BACKEND / "build"
STAGE = BUILD / "lambda"
ZIP_PATH = BUILD / "lambda.zip"
REQUIREMENTS = BACKEND / "requirements-lambda.txt"

# Lambda 的限制：解壓後（含 layer）250 MB；直接上傳 zip 50 MB，超過要先放 S3（deploy.py 一律走 S3）。
UNZIPPED_LIMIT = 250 * 1024 * 1024
WARN_MARGIN = 10 * 1024 * 1024

# 執行時用不到的東西，拿掉以縮小體積。
PRUNE_DIRS = {"__pycache__", "tests", "test"}
PRUNE_SUFFIXES = {".pyc", ".pyo"}


def install_packages():
    # PYTHONUTF8：Windows 上 pip 預設用系統編碼（cp950）讀 requirements，遇到中文註解就失敗。
    env = {**os.environ, "PYTHONUTF8": "1"}
    subprocess.run(
        [
            sys.executable, "-m", "pip", "install",
            "--quiet", "--disable-pip-version-check",
            "--platform", "manylinux2014_x86_64",
            "--implementation", "cp",
            "--python-version", "3.12",
            "--only-binary=:all:",
            "--target", str(STAGE),
            "-r", str(REQUIREMENTS),
        ],
        check=True,
        env=env,
    )


def copy_app():
    shutil.copytree(
        BACKEND / "app",
        STAGE / "app",
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
    )


def prune():
    for path in sorted(STAGE.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if path.is_dir() and path.name in PRUNE_DIRS and "app" not in path.relative_to(STAGE).parts[:1]:
            shutil.rmtree(path, ignore_errors=True)
        elif path.is_file() and path.suffix in PRUNE_SUFFIXES:
            path.unlink()
    # OpenCV 內附的 Haar 人臉偵測模型（約 10 MB），本專案沒有用到。只刪 xml、保留 data/ 套件本身，
    # cv2 啟動時會掃描子套件，整個資料夾拿掉反而可能出錯。
    for xml in (STAGE / "cv2" / "data").glob("*.xml"):
        xml.unlink()
    # pip 安裝的命令列小工具，Lambda 用不到。
    shutil.rmtree(STAGE / "bin", ignore_errors=True)


def make_zip() -> int:
    total = 0
    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted(STAGE.rglob("*")):
            if path.is_file():
                total += path.stat().st_size
                # 路徑一律用 /，Lambda（Linux）才找得到模組。
                z.write(path, path.relative_to(STAGE).as_posix())
    return total


def main() -> int:
    if BUILD.exists():
        shutil.rmtree(BUILD)
    STAGE.mkdir(parents=True)

    print("下載 Linux 版套件⋯")
    install_packages()
    copy_app()
    prune()
    unzipped = make_zip()

    mb = 1024 * 1024
    print(f"完成：{ZIP_PATH}")
    print(f"  zip {ZIP_PATH.stat().st_size / mb:.1f} MB，解壓後 {unzipped / mb:.1f} MB（上限 {UNZIPPED_LIMIT / mb:.0f} MB）")
    if unzipped > UNZIPPED_LIMIT:
        print("超過 Lambda 的解壓上限，部署會失敗。檢查 requirements-lambda.txt 是否多裝了大型套件。")
        return 1
    if unzipped > UNZIPPED_LIMIT - WARN_MARGIN:
        # opencv 與 numpy 各自帶一份 OpenBLAS（各約 36 MB），整包本來就接近上限；升級套件前先看這個數字。
        print(f"⚠️ 離上限不到 {WARN_MARGIN / mb:.0f} MB，升級任何套件都可能超過。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
