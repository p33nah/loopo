import json
import tempfile
import unittest
from pathlib import Path

from loopo_functiongemma.dataset import generate_dataset, validate_dataset, write_dataset
from loopo_functiongemma.tools import NO_CALL_TEXT, TOOLS, to_conversation, validate_plan


class DatasetTests(unittest.TestCase):
    def test_base_corpus_is_deterministic_and_about_five_hundred_rows(self) -> None:
        first = generate_dataset()
        second = generate_dataset()
        self.assertEqual(first, second)
        base_count = sum(len(first[name]) for name in ("train", "validation", "test"))
        self.assertGreaterEqual(base_count, 450)
        self.assertLessEqual(base_count, 550)
        variants = {row["variant"] for name in ("train", "validation", "test") for row in first[name]}
        self.assertTrue({"clean", "natural", "dialect", "synthetic_asr"} <= variants)

    def test_families_do_not_cross_splits(self) -> None:
        data = generate_dataset()
        split_families = {
            split: {row["family"] for row in data[split]}
            for split in ("train", "validation", "test")
        }
        self.assertFalse(split_families["train"] & split_families["validation"])
        self.assertFalse(split_families["train"] & split_families["test"])
        self.assertFalse(split_families["validation"] & split_families["test"])
        base_families = set.union(*split_families.values())
        self.assertFalse(base_families & {row["family"] for row in data["challenge"]})
        for split in data.values():
            self.assertTrue(any(row["expected"] for row in split))
            self.assertTrue(any(not row["expected"] for row in split))

    def test_generation_and_validation_write_all_four_splits(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            counts = write_dataset(temp_dir)
            self.assertEqual(counts, validate_dataset(temp_dir))
            self.assertEqual(
                {"train.jsonl", "validation.jsonl", "test.jsonl", "challenge.jsonl"},
                {path.name for path in Path(temp_dir).iterdir()},
            )

    def test_expansion_changes_only_training_split(self) -> None:
        base = generate_dataset()
        expanded = generate_dataset(expand_train=2000)
        self.assertEqual(len(expanded["train"]), 2000)
        for split in ("validation", "test", "challenge"):
            self.assertEqual(expanded[split], base[split])
        self.assertTrue(all(row["source"] == "generated_expansion" for row in expanded["train"][len(base["train"]):]))

    def test_duplicate_transcript_conflicts_are_detected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            write_dataset(temp_dir)
            path = Path(temp_dir) / "test.jsonl"
            train = Path(temp_dir) / "train.jsonl"
            first = json.loads(train.read_text(encoding="utf-8").splitlines()[0])
            duplicate = {**first, "id": "contradictory", "family": "new_family", "expected": []}
            duplicate["text"] = duplicate["text"].upper() + "!"
            with path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(duplicate) + "\n")
            with self.assertRaisesRegex(ValueError, "contradictory labels"):
                validate_dataset(temp_dir)

    def test_checked_in_splits_match_generator(self) -> None:
        committed = Path(__file__).resolve().parents[1] / "data"
        with tempfile.TemporaryDirectory() as temp_dir:
            write_dataset(temp_dir)
            for path in committed.glob("*.jsonl"):
                self.assertEqual(path.read_bytes(), (Path(temp_dir) / path.name).read_bytes())

    def test_plan_schema_and_native_conversation(self) -> None:
        plan = [
            {"name": "set_rate", "arguments": {"percent": 125}},
            {"name": "restart", "arguments": {}},
        ]
        self.assertEqual(validate_plan(plan), plan)
        row = {"text": "Imposta la velocità al 125% e poi ricomincia", "expected": plan}
        conversation = to_conversation(row)
        self.assertEqual(conversation["tools"], TOOLS)
        self.assertEqual(conversation["messages"][1]["role"], "user")
        self.assertEqual(conversation["messages"][2]["tool_calls"][1]["function"]["name"], "restart")
        rejected = to_conversation({"text": "non riprendere", "expected": []})
        self.assertEqual(rejected["messages"][2]["content"], NO_CALL_TEXT)

    def test_plan_rejects_unsupported_sequence_and_bad_rate(self) -> None:
        with self.assertRaises(ValueError):
            validate_plan([
                {"name": "resume", "arguments": {}},
                {"name": "set_rate", "arguments": {"percent": 100}},
            ])
        with self.assertRaises(ValueError):
            validate_plan([{"name": "set_rate", "arguments": {"percent": 201}}])
        with self.assertRaises(ValueError):
            validate_plan([{"name": "adjust_rate", "arguments": {"delta_percent": 0}}])


if __name__ == "__main__":
    unittest.main()
