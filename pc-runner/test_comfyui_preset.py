import json
from pathlib import Path
import tempfile
import unittest

import comfyui_preset as preset


class PresetTests(unittest.TestCase):
    def setUp(self):
        self.graph = preset.graph("test.safetensors", "a tree", "", 512, 512, 20, 42)

    def test_graph(self):
        self.assertEqual(self.graph["5"]["inputs"]["seed"], 42)
        self.assertEqual(self.graph["4"]["inputs"]["batch_size"], 1)
        self.assertEqual(self.graph["7"]["inputs"]["images"], ["6", 0])
        for size in (255, 513, 2048, True):
            with self.assertRaises(ValueError):
                preset.graph("a", "b", "", size, 512, 20, 0)

    def test_result(self):
        state = {}
        calls = []
        def fake(path, body=None, **kwargs):
            calls.append(path)
            if path == "/prompt":
                state["id"] = body["prompt_id"]
                return json.dumps({"prompt_id": state["id"]}).encode()
            if path.startswith("/api/jobs/"):
                return b'{"status":"completed"}'
            if path.startswith("/history/"):
                return json.dumps({state["id"]: {"outputs": {"7": {"images": [
                    {"type": "output", "filename": "test & image.png", "subfolder": ""}
                ]}}}}).encode()
            self.assertIn("test+%26+image.png", path)
            self.assertEqual(kwargs["limit"], preset.MAX_IMAGE)
            return preset.PNG + b"fixture"
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "result.png"
            preset.generate(self.graph, target, 5, call=fake)
            self.assertEqual(target.read_bytes(), preset.PNG + b"fixture")
        self.assertFalse(any(p.endswith("/cancel") for p in calls))

    def test_failed_job_cancels_only_its_id(self):
        state = {}
        def fake(path, body=None, **kwargs):
            if path == "/prompt":
                state["id"] = body["prompt_id"]
                return json.dumps({"prompt_id": state["id"]}).encode()
            if path.endswith("/cancel"):
                self.assertEqual(path, "/api/jobs/" + state["id"] + "/cancel")
                state["cancelled"] = True
                return b'{"cancelled":false}'
            return b'{"status":"failed"}'
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "result.png"
            with self.assertRaises(RuntimeError):
                preset.generate(self.graph, target, 5, call=fake)
            self.assertFalse(target.exists())
        self.assertTrue(state["cancelled"])

    def test_non_png_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "result.png"
            with self.assertRaises(RuntimeError):
                preset.save_png(b"not an image", target)
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
