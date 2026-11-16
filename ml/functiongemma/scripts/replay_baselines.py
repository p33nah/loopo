#!/usr/bin/env python3
"""Replay JSONL transcripts through Loopo's parser and pinned PR #67 candidate."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import platform
import shutil
from pathlib import Path
import subprocess
import tempfile


PINNED_COMMIT = "9465326b8feb99243bd70e31c2fcaf258ecdf72f"
BASELINE_FILES = (
    "app/src/androidTest/java/com/p33nah/loopo/ui/voice/ItalianIntentClassifier.kt",
    "app/src/androidTest/assets/voice-intent-ab/training.txt",
    "app/src/androidTest/assets/voice-intent-ab/training-extra.txt",
)
KOTLIN_FILES = BASELINE_FILES[:1]
TRAINING_FILES = BASELINE_FILES[1:]
REPO_ROOT = Path(__file__).resolve().parents[3]
BRIDGE_TEST = "com.p33nah.loopo.ui.voice.ParserBenchmarkBridgeTest"
BRIDGE_FIXTURE = Path(__file__).with_name("ParserBenchmarkBridge.kt")
REPLAY_BUILD_DIR = BRIDGE_FIXTURE.parents[1] / "runs" / "jvm-replay-build"


def git_prefix() -> list[str]:
    marker = REPO_ROOT / ".git"
    if marker.is_dir():
        return ["git", "-C", str(REPO_ROOT)]
    pointer = marker.read_text(encoding="utf-8").strip()
    if not pointer.startswith("gitdir:"):
        raise RuntimeError(f"Cannot resolve Git worktree metadata in {marker}")
    git_dir = Path(pointer.partition(":")[2].strip())
    if not git_dir.exists() and os.name == "posix":
        converted = subprocess.run(
            ["wslpath", "-u", str(git_dir)], check=True, text=True, capture_output=True
        ).stdout.strip()
        git_dir = Path(converted)
    if not git_dir.exists():
        raise RuntimeError(f"Git worktree metadata does not exist: {git_dir}")
    return ["git", f"--git-dir={git_dir}", f"--work-tree={REPO_ROOT}"]


def git_show(path: str) -> bytes:
    return subprocess.run(
        [*git_prefix(), "show", f"{PINNED_COMMIT}:{path}"],
        cwd=REPO_ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout


def ensure_pinned_commit() -> None:
    present = subprocess.run(
        [*git_prefix(), "cat-file", "-e", f"{PINNED_COMMIT}^{{commit}}"],
        cwd=REPO_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0
    if not present:
        subprocess.run([*git_prefix(), "fetch", "origin", PINNED_COMMIT], cwd=REPO_ROOT, check=True)


def write_pinned_baseline(root: Path) -> tuple[Path, Path]:
    source_root = root / "source"
    training_root = root / "training"
    for relative in KOTLIN_FILES:
        target = source_root / Path(relative).relative_to("app/src/androidTest/java")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git_show(relative))
    fixture = source_root / "com/p33nah/loopo/ui/voice/ParserBenchmarkBridgeTest.kt"
    fixture.parent.mkdir(parents=True, exist_ok=True)
    fixture.write_bytes(BRIDGE_FIXTURE.read_bytes())
    for relative in TRAINING_FILES:
        target = training_root / Path(relative).name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git_show(relative))
    return source_root, training_root


def write_init_script(path: Path) -> None:
    path.write_text(
        """gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.layout.buildDirectory.set(project.file(project.property('loopoBenchmarkBuildDir')))
    }
}
allprojects { project ->
    afterEvaluate {
        if (project.path == ':app') {
            def candidateSources = project.file(project.property('loopoBenchmarkSourceDir'))
            def android = project.extensions.getByName('android')
            android.sourceSets.findAll { it.name == 'test' || it.name == 'testDebug' }.each {
                it.java.srcDir(candidateSources)
            }
            def kotlin = project.extensions.findByName('kotlin')
            kotlin?.sourceSets?.findAll { it.name == 'test' || it.name == 'testDebug' }?.each {
                it.kotlin.srcDir(candidateSources)
            }
            project.tasks.matching { it.name == 'compileDebugUnitTestKotlin' }.configureEach {
                it.setSource(project.files(candidateSources))
            }
        }
    }
}
""",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Input JSONL with id and text columns")
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory for paired outputs named from the input file")
    args = parser.parse_args()

    input_path = args.input.resolve(strict=True)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    parser_output = output_dir / f"{input_path.stem}.parser.jsonl"
    classifier_output = output_dir / f"{input_path.stem}.classifier.jsonl"
    dataset_sha256 = hashlib.sha256(input_path.read_bytes()).hexdigest()
    rows = [json.loads(line) for line in input_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    for index, row in enumerate(rows, start=1):
        if not isinstance(row.get("id"), str) or not row["id"]:
            parser.error(f"JSONL row {index} must have a non-empty string id")
        if not isinstance(row.get("text"), str):
            parser.error(f"JSONL row {index} must have a string text")
    expected_ids = {row["id"] for row in rows}
    if not rows or len(expected_ids) != len(rows):
        parser.error("input must contain unique IDs and at least one row")
    normalized_input = "".join(
        base64.b64encode(row["id"].encode("utf-8")).decode("ascii") + "\t" +
        base64.b64encode(row["text"].encode("utf-8")).decode("ascii") + "\n"
        for row in rows
    )
    ensure_pinned_commit()

    with tempfile.TemporaryDirectory(prefix="loopo-pr67-replay-") as temporary:
        temporary_root = Path(temporary)
        source_root, training_root = write_pinned_baseline(temporary_root)
        normalized_input_path = temporary_root / "input.tsv"
        normalized_input_path.write_text(normalized_input, encoding="ascii")
        init_script = temporary_root / "benchmark.init.gradle"
        write_init_script(init_script)
        parser_tmp = temporary_root / parser_output.name
        classifier_tmp = temporary_root / classifier_output.name
        environment = os.environ.copy()
        environment.update(
            LOOPO_BENCH_INPUT_TSV=str(normalized_input_path),
            LOOPO_BENCH_PARSER_OUTPUT=str(parser_tmp),
            LOOPO_BENCH_CLASSIFIER_OUTPUT=str(classifier_tmp),
            LOOPO_BENCH_TRAINING_DIR=str(training_root),
        )
        subprocess.run(
            [
                "bash", "gradlew", "--init-script", str(init_script),
                "--project-cache-dir", str(REPLAY_BUILD_DIR.parent / "jvm-replay-cache"),
                f"-PloopoBenchmarkBuildDir={REPLAY_BUILD_DIR}",
                f"-PloopoBenchmarkSourceDir={source_root}",
                ":app:testDebugUnitTest", "--rerun", "--tests", BRIDGE_TEST, "--console=plain",
            ],
            cwd=REPO_ROOT,
            env=environment,
            check=True,
        )
        for temporary_output, output in ((parser_tmp, parser_output), (classifier_tmp, classifier_output)):
            replay_ids = [
                json.loads(line)["id"]
                for line in temporary_output.read_text(encoding="utf-8").splitlines()
            ]
            if len(replay_ids) != len(rows) or set(replay_ids) != expected_ids:
                raise ValueError(f"{temporary_output.name} did not replay every input row exactly once")
            shutil.copyfile(temporary_output, output)
    source_commit = subprocess.check_output([*git_prefix(), "rev-parse", "HEAD"], text=True).strip()
    for output, baseline in (
        (parser_output, "production-parser"),
        (classifier_output, "pr67-expanded-classifier"),
    ):
        output.with_name(f"{output.stem}.metadata.json").write_text(
            json.dumps(
                {
                    "baseline": baseline,
                    "dataset_sha256": dataset_sha256,
                    "source_commit": source_commit,
                    "platform": platform.platform(),
                    "timing_scope": "one warm JVM interpretation per transcript",
                    "classifier_commit": PINNED_COMMIT if baseline.startswith("pr67") else None,
                },
                indent=2,
            ) + "\n",
            encoding="utf-8",
        )
    print(f"Production parser: {parser_output}")
    print(f"PR #67 classifier: {classifier_output}")


if __name__ == "__main__":
    main()
