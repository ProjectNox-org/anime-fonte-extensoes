import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("merge-repo.py")
PREFIX = "eu.kanade.tachiyomi.animeextension."


class MergeRepoTorrentTest(unittest.TestCase):
    def test_preserves_torrent_capability_in_existing_catalog_without_builds(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "main" / "src" / "all"
            source.joinpath("torrent").mkdir(parents=True)
            (source / "torrent" / "build.gradle.kts").write_text(
                "yuzuno {\n    torrent = true\n}\n", encoding="utf-8"
            )
            source.joinpath("regular").mkdir()
            (source / "regular" / "build.gradle.kts").write_text(
                "yuzuno {\n}\n", encoding="utf-8"
            )

            remote = root / "repo"
            (remote / "apk").mkdir(parents=True)
            (remote / "icon").mkdir()
            local = root / "main" / "repo"
            (local / "apk").mkdir(parents=True)
            index = []
            for module in ("all.torrent", "all.regular"):
                package = PREFIX + module
                apk = f"aniyomi-{module}-v14.1.apk"
                index.append(
                    {
                        "name": module,
                        "pkg": package,
                        "apk": apk,
                        "lang": "all",
                        "code": 1,
                        "version": "14.1",
                        "nsfw": 0,
                        "sources": [],
                    }
                )
                (remote / "apk" / apk).write_bytes(b"existing apk")
                (remote / "icon" / f"{package}.png").write_bytes(b"existing icon")

            (remote / "index.json").write_text(json.dumps(index), encoding="utf-8")
            (local / "index.min.json").write_text("[]", encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "[]", "main/repo"],
                cwd=remote,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

            for catalog_name in ("index.json", "index.min.json"):
                published = json.loads((remote / catalog_name).read_text(encoding="utf-8"))
                by_package = {item["pkg"]: item for item in published}
                torrent_val = by_package[PREFIX + "all.torrent"]["torrent"]
                self.assertEqual(torrent_val, 1)
                self.assertIs(type(torrent_val), int)
                self.assertNotIn("torrent", by_package[PREFIX + "all.regular"])

    def test_reconciles_apostrophe_names_and_mixed_content_warning_without_rebuilds(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            src = root / "main" / "src"
            (src / "es" / "samatodenvideos").mkdir(parents=True)
            (src / "es" / "samatodenvideos" / "build.gradle.kts").write_text(
                'yuzono {\n    name = "Samato\'s Den: Videos"\n    contentWarning = ContentWarning.NSFW\n}\n',
                encoding="utf-8",
            )
            (src / "all" / "movy").mkdir(parents=True)
            (src / "all" / "movy" / "build.gradle.kts").write_text(
                'yuzono {\n    name = "Movy"\n    contentWarning = ContentWarning.MIXED\n}\n',
                encoding="utf-8",
            )
            (src / "en" / "safe").mkdir(parents=True)
            (src / "en" / "safe" / "build.gradle.kts").write_text(
                'yuzono {\n    name = "Safe & Clean"\n    contentWarning = ContentWarning.SAFE\n}\n',
                encoding="utf-8",
            )

            remote = root / "repo"
            (remote / "apk").mkdir(parents=True)
            (remote / "icon").mkdir()
            local = root / "main" / "repo"
            (local / "apk").mkdir(parents=True)
            index = []
            for mod, stale_name, nsfw in (
                ("es.samatodenvideos", "Aniyomi: Samato", 1),
                ("all.movy", "Aniyomi: Movy", 1),
                ("en.safe", "Aniyomi: Safe & Clean", 0),
            ):
                pkg = PREFIX + mod
                apk = f"aniyomi-{mod}-v16.1.apk"
                index.append(
                    {
                        "name": stale_name,
                        "pkg": pkg,
                        "apk": apk,
                        "lang": mod.split(".")[0],
                        "code": 1,
                        "version": "16.1",
                        "nsfw": nsfw,
                        "sources": [],
                    }
                )
                (remote / "apk" / apk).write_bytes(b"existing apk")
                (remote / "icon" / f"{pkg}.png").write_bytes(b"existing icon")

            (remote / "index.json").write_text(json.dumps(index), encoding="utf-8")
            (local / "index.min.json").write_text("[]", encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "[]", "main/repo"],
                cwd=remote,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

            full_by_pkg = {item["pkg"]: item for item in json.loads((remote / "index.json").read_text(encoding="utf-8"))}
            min_by_pkg = {item["pkg"]: item for item in json.loads((remote / "index.min.json").read_text(encoding="utf-8"))}

            self.assertEqual(full_by_pkg[PREFIX + "es.samatodenvideos"]["name"], "Aniyomi: Samato's Den: Videos")
            self.assertEqual(min_by_pkg[PREFIX + "es.samatodenvideos"]["name"], "Aniyomi: Samato's Den: Videos")
            self.assertEqual(full_by_pkg[PREFIX + "es.samatodenvideos"]["contentWarning"], "NSFW")
            self.assertEqual(full_by_pkg[PREFIX + "all.movy"]["contentWarning"], "MIXED")
            self.assertEqual(full_by_pkg[PREFIX + "all.movy"]["nsfw"], 1)
            self.assertEqual(full_by_pkg[PREFIX + "en.safe"]["contentWarning"], "SAFE")
            self.assertNotIn("contentWarning", min_by_pkg[PREFIX + "all.movy"])


if __name__ == "__main__":
    unittest.main()
