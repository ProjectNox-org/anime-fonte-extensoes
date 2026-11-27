import argparse
import sys
sys.dont_write_bytecode = True
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
CONTENT_WARNING_REGEX = re.compile(r"'aniyomix\.contentWarning' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'(.*)'$", re.MULTILINE)
ANIYOMIX_NAME_REGEX = re.compile(r"meta-data: name='aniyomix\.name' value='(.*)'$", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"aniyomi-([^.]+)")

CONTENT_WARNING_FROM_PROTO = {
    1: "SAFE",
    2: "MIXED",
    3: "NSFW",
}
CONTENT_WARNING_FROM_MANIFEST = {
    "0": "SAFE",
    "1": "MIXED",
    "2": "NSFW",
}


def _unescape_aapt_value(raw: str) -> str:
    if "\\" not in raw:
        return raw
    return (
        raw.replace("\\'", "'")
        .replace('\\"', '"')
        .replace("\\\\", "\\")
    )


def _extract_application_label(badging: str, source_meta: dict | None = None) -> str:
    if isinstance(source_meta, dict):
        ext_name = source_meta.get("name")
        if isinstance(ext_name, str) and ext_name:
            return f"Aniyomi: {ext_name}"
    label_match = APPLICATION_LABEL_REGEX.search(badging)
    if label_match:
        return _unescape_aapt_value(label_match.group(1))
    meta_name_match = ANIYOMIX_NAME_REGEX.search(badging)
    if meta_name_match:
        return f"Aniyomi: {_unescape_aapt_value(meta_name_match.group(1))}"
    raise ValueError("Unable to extract application label from APK badging")


def _extract_content_warning(badging: str, nsfw: int, source_meta: dict | None = None) -> str:
    if isinstance(source_meta, dict):
        cw_raw = source_meta.get("contentWarning")
        if isinstance(cw_raw, int) and cw_raw in CONTENT_WARNING_FROM_PROTO:
            return CONTENT_WARNING_FROM_PROTO[cw_raw]
    cw_match = CONTENT_WARNING_REGEX.search(badging)
    if cw_match and cw_match.group(1) in CONTENT_WARNING_FROM_MANIFEST:
        return CONTENT_WARNING_FROM_MANIFEST[cw_match.group(1)]
    return "NSFW" if nsfw == 1 else "SAFE"

REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"
SOURCE_INFO_DIR = Path("source-info")


def _get_aapt() -> Path:
    build_tools = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir())
    if not build_tools:
        raise RuntimeError("No Android build-tools found under ANDROID_HOME")
    return build_tools[-1] / "aapt"


def _aapt_badging(apk: Path) -> str:
    return subprocess.check_output(
        [str(_get_aapt()), "dump", "--include-meta-data", "badging", str(apk)]
    ).decode()


def resolve_inspector_and_source_info(
    apk_dir: Path = REPO_APK_DIR,
    source_info_dir: Path = SOURCE_INFO_DIR,
    output_path: Path = Path("output.json"),
) -> dict:
    """Build output.json using compile-time keiyoushi-source-info.json first and Inspector.jar only for legacy APKs."""
    source_info_by_pkg: dict[str, list[dict]] = {}
    if source_info_dir.is_dir():
        for info_file in sorted(source_info_dir.glob("*.json")):
            info = json.loads(info_file.read_text(encoding="utf-8"))
            pkg = info.get("packageName")
            if pkg:
                source_info_by_pkg[pkg] = [
                    {
                        "name": src["name"],
                        "lang": src["lang"],
                        "id": str(src["id"]),
                        "baseUrl": src["baseUrl"],
                        "versionId": int(src.get("versionId", 1)),
                    }
                    for src in info.get("sources", [])
                ]

    output_data: dict[str, list[dict]] = {}
    legacy_apks: list[Path] = []
    if apk_dir.is_dir():
        for apk in sorted(apk_dir.iterdir()):
            if not apk.is_file() or apk.suffix != ".apk":
                continue
            badging = _aapt_badging(apk)
            package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
            pkg = PACKAGE_NAME_REGEX.search(package_info)[1]
            if pkg in source_info_by_pkg:
                output_data[pkg] = source_info_by_pkg[pkg]
            else:
                legacy_apks.append(apk)

    if legacy_apks:
        inspector_jar = Path("Inspector.jar")
        if not inspector_jar.is_file():
            subprocess.check_call(
                [sys.executable, str(Path(__file__).with_name("fetch_inspector.py")), "-o", str(inspector_jar)]
            )
        with tempfile.TemporaryDirectory(prefix="nox-legacy-inspector-") as tmpdir:
            tmp_root = Path(tmpdir)
            leg_apk_dir = tmp_root / "apk"
            leg_work_dir = tmp_root / "work"
            leg_out_file = tmp_root / "legacy-output.json"
            leg_apk_dir.mkdir(parents=True)
            for apk in legacy_apks:
                shutil.copy2(apk, leg_apk_dir / apk.name)
            subprocess.check_call(
                ["java", "-jar", str(inspector_jar), str(leg_apk_dir), str(leg_out_file), str(leg_work_dir)]
            )
            legacy_data = json.loads(leg_out_file.read_text(encoding="utf-8"))
            output_data.update(legacy_data)

    output_path.write_text(json.dumps(output_data, ensure_ascii=False), encoding="utf-8")
    return output_data


def create_repo(resolve_sources: bool = False) -> None:
    REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)
    output_path = Path("output.json")
    if resolve_sources or not output_path.exists():
        inspector_data = resolve_inspector_and_source_info(output_path=output_path)
    else:
        with output_path.open(encoding="utf-8") as f:
            inspector_data = json.load(f)

    index_min_data = []
    source_meta_by_pkg: dict[str, dict] = {}
    if SOURCE_INFO_DIR.is_dir():
        for info_file in sorted(SOURCE_INFO_DIR.glob("*.json")):
            try:
                info = json.loads(info_file.read_text(encoding="utf-8"))
                pkg = info.get("packageName")
                if pkg:
                    source_meta_by_pkg[pkg] = info
            except (OSError, json.JSONDecodeError):
                pass

    for apk in sorted(REPO_APK_DIR.iterdir()):
        if not apk.is_file() or apk.suffix != ".apk":
            continue
        badging = _aapt_badging(apk)

        package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
        package_name = PACKAGE_NAME_REGEX.search(package_info)[1]
        application_icon = APPLICATION_ICON_320_REGEX.search(badging)[1]

        with ZipFile(apk) as z, z.open(application_icon) as i, (
            REPO_ICON_DIR / f"{package_name}.png"
        ).open("wb") as f:
            f.write(i.read())

        language = LANGUAGE_REGEX.search(apk.name)[1]
        sources = inspector_data[package_name]

        if len(sources) == 1:
            source_language = sources[0]["lang"]

            if (
                source_language != language
                and source_language not in {"all", "other"}
                and language not in {"all", "other"}
            ):
                language = source_language

        source_meta = source_meta_by_pkg.get(package_name)
        nsfw = int(IS_NSFW_REGEX.search(badging)[1])
        content_warning = _extract_content_warning(badging, nsfw, source_meta=source_meta)
        common_data = {
            "name": _extract_application_label(badging, source_meta=source_meta),
            "pkg": package_name,
            "apk": apk.name,
            "lang": language,
            "code": int(VERSION_CODE_REGEX.search(package_info)[1]),
            "version": VERSION_NAME_REGEX.search(package_info)[1],
            "nsfw": nsfw,
            "contentWarning": content_warning,
        }
        min_data = {
            **common_data,
            "sources": [],
        }

        for source in sources:
            min_data["sources"].append(
                {
                    "name": source["name"],
                    "lang": source["lang"],
                    "id": source["id"],
                    "baseUrl": source["baseUrl"],
                    "versionId": source["versionId"],
                }
            )

        index_min_data.append(min_data)

    with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as index_file:
        json.dump(index_min_data, index_file, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resolve-sources", action="store_true")
    args = parser.parse_args()
    create_repo(resolve_sources=args.resolve_sources)
