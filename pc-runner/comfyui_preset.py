#!/usr/bin/env python3
"""Owner-configured text-to-image preset for the existing VictorBots PC runner."""
import argparse
import http.client
import json
import os
from pathlib import Path
import signal
import tempfile
import time
from urllib.parse import urlencode
import uuid

MAX_JSON = 2 * 1024 * 1024
MAX_IMAGE = 32 * 1024 * 1024
PNG = b"\x89PNG\r\n\x1a\n"


def request(path, body=None, *, limit=MAX_JSON, timeout=10):
    # No caller-controlled host, proxy environment, redirects or credentials.
    conn = http.client.HTTPConnection("127.0.0.1", 8188, timeout=timeout)
    try:
        conn.request("GET" if body is None else "POST", path,
                     None if body is None else json.dumps(body),
                     {} if body is None else {"Content-Type": "application/json"})
        response = conn.getresponse()
        if response.status != 200:
            raise RuntimeError("ComfyUI returned HTTP %d" % response.status)
        data = response.read(limit + 1)
        if len(data) > limit:
            raise RuntimeError("ComfyUI response exceeds limit")
        return data
    finally:
        conn.close()


def graph(checkpoint, prompt, negative, width, height, steps, seed):
    if not checkpoint or len(checkpoint) > 512 or not prompt.strip() or len(prompt) > 2000 or len(negative) > 2000:
        raise ValueError("invalid checkpoint or prompt")
    if any(type(n) is not int or n < 256 or n > 1024 or n % 64 for n in (width, height)):
        raise ValueError("dimensions must be 256–1024 in multiples of 64")
    if type(steps) is not int or not 1 <= steps <= 60 or type(seed) is not int or not 0 <= seed <= 4294967295:
        raise ValueError("invalid steps or seed")
    return {
        "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": checkpoint}},
        "2": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["1", 1], "text": prompt}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["1", 1], "text": negative}},
        "4": {"class_type": "EmptyLatentImage", "inputs": {"width": width, "height": height, "batch_size": 1}},
        "5": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "positive": ["2", 0], "negative": ["3", 0], "latent_image": ["4", 0], "seed": seed, "steps": steps, "cfg": 7.0, "sampler_name": "euler", "scheduler": "normal", "denoise": 1.0}},
        "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage", "inputs": {"images": ["6", 0], "filename_prefix": "VictorBots"}}
    }


def save_png(data, target):
    if not data.startswith(PNG):
        raise RuntimeError("ComfyUI output is not a PNG")
    # Replace the directory entry atomically; never follow an existing output symlink.
    fd, name = tempfile.mkstemp(prefix=".comfyui-", dir=str(target.parent))
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
        os.replace(name, target)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def generate(workflow, target, timeout, call=request, sleep=time.sleep):
    job_id = str(uuid.uuid4())
    deadline = time.monotonic() + timeout
    complete = False
    attempted = False
    try:
        attempted = True
        result = json.loads(call("/prompt", {"prompt": workflow, "prompt_id": job_id}))
        if result.get("prompt_id") != job_id:
            raise RuntimeError("ComfyUI did not confirm the supplied job ID")
        print("ComfyUI job:", job_id, flush=True)
        while time.monotonic() < deadline:
            job = json.loads(call("/api/jobs/" + job_id))
            status = job.get("status")
            if status in ("failed", "cancelled"):
                raise RuntimeError("ComfyUI job " + status)
            if status == "completed":
                complete = True
                history = json.loads(call("/history/" + job_id))
                images = history.get(job_id, {}).get("outputs", {}).get("7", {}).get("images", [])
                if len(images) != 1:
                    raise RuntimeError("expected exactly one saved image")
                image = images[0]
                if image.get("type") != "output" or not isinstance(image.get("filename"), str):
                    raise RuntimeError("invalid output descriptor")
                query = urlencode({"filename": image["filename"], "subfolder": image.get("subfolder", ""), "type": "output"})
                data = call("/view?" + query, limit=MAX_IMAGE)
                save_png(data, target)
                return
            if status not in ("pending", "in_progress"):
                raise RuntimeError("unrecognized ComfyUI job status")
            sleep(2)
        raise TimeoutError("ComfyUI job exceeded deadline")
    finally:
        if attempted and not complete:
            try:
                call("/api/jobs/" + job_id + "/cancel", {}, timeout=2)
            except Exception:
                print("Cancellation not confirmed; inspect local ComfyUI queue:", job_id, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--prompt", required=True)
    parser.add_argument("--negative", default="")
    parser.add_argument("--width", type=int, default=512)
    parser.add_argument("--height", type=int, default=512)
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    if not 5 <= args.timeout <= 1800:
        parser.error("timeout must be 5–1800 seconds")
    def stop(_signum, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    try:
        workflow = graph(args.checkpoint, args.prompt, args.negative, args.width, args.height, args.steps, args.seed)
        generate(workflow, Path.cwd() / "comfyui-result.png", args.timeout)
        print("Saved comfyui-result.png", flush=True)
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print("Generation failed:", type(exc).__name__, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
