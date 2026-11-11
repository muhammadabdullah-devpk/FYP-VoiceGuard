"""
Export AASIST-L (PyTorch) to ONNX for the VoiceGuard Android app.

Usage (from any folder):
    git clone https://github.com/clovaai/aasist
    pip install torch numpy onnx onnxruntime
    python export_aasist_l_onnx.py --repo ./aasist --out voiceguard_aasist_l.onnx

To use your OWN fine-tuned Urdu model (milestones M3 to M5), pass its checkpoint:
    python export_aasist_l_onnx.py --repo ./aasist --weights my_urdu_aasist_l.pth --out voiceguard_aasist_l.onnx

Then copy the .onnx file to  app/src/main/assets/  in Android Studio.

Model contract expected by the app (do not change):
    input  "audio_waveform"  float32 [1, 64600]   (16 kHz mono, about 4.03 s, values in [-1, 1])
    output "logits"          float32 [1, 2]       ([spoof, bona fide] logits)

AASIST label convention: class 1 = bona fide (real), class 0 = spoof.
The official AASIST-L weights were trained on ASVspoof 2019 LA (English) only.
They are the baseline of the proposal and are expected to perform near chance level on Urdu
until you fine-tune them on the Urdu / call-domain data.
"""
import argparse
import json
import sys
import time

import numpy as np
import torch
import torch.nn as nn


class ExportWrapper(nn.Module):
    """AASIST returns (embedding, logits). The app only needs the logits."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        _, logits = self.model(x)
        return logits


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="Path of the cloned clovaai/aasist repository")
    parser.add_argument("--weights", default=None, help="Checkpoint (.pth). Default: repo AASIST-L.pth")
    parser.add_argument("--out", default="voiceguard_aasist_l.onnx")
    parser.add_argument("--opset", type=int, default=14)
    args = parser.parse_args()

    sys.path.insert(0, args.repo)
    from models.AASIST import Model  # noqa: E402

    with open(f"{args.repo}/config/AASIST-L.conf") as f:
        cfg = json.load(f)
    model = Model(cfg["model_config"])
    weights = args.weights or f"{args.repo}/models/weights/AASIST-L.pth"
    state = torch.load(weights, map_location="cpu")
    model.load_state_dict(state)
    model.eval()
    n_params = sum(p.numel() for p in model.parameters())
    print(f"Loaded {weights}  parameters: {n_params}")

    wrapper = ExportWrapper(model).eval()
    dummy = torch.randn(1, 64600) * 0.1

    export_kwargs = dict(
        input_names=["audio_waveform"],
        output_names=["logits"],
        opset_version=args.opset,
        do_constant_folding=True,
    )
    try:
        # Newer PyTorch versions use the dynamo exporter by default. The classic exporter is
        # more predictable for this model.
        torch.onnx.export(wrapper, dummy, args.out, dynamo=False, **export_kwargs)
    except TypeError:
        torch.onnx.export(wrapper, dummy, args.out, **export_kwargs)
    print(f"Wrote {args.out}")

    # ---- verify: ONNX Runtime must give the same logits as PyTorch ----
    import onnxruntime as ort

    sess = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    print("ONNX input :", [(i.name, i.shape, i.type) for i in sess.get_inputs()])
    print("ONNX output:", [(o.name, o.shape, o.type) for o in sess.get_outputs()])

    worst = 0.0
    for seed in range(5):
        g = torch.Generator().manual_seed(seed)
        x = torch.randn(1, 64600, generator=g) * 0.1
        with torch.no_grad():
            ref = wrapper(x).numpy()
        got = sess.run(None, {"audio_waveform": x.numpy()})[0]
        worst = max(worst, float(np.abs(ref - got).max()))
    print(f"Max |PyTorch - ONNX| over 5 random inputs: {worst:.2e}")
    if worst > 1e-3:
        print("WARNING: outputs differ more than expected")

    x = (np.random.randn(1, 64600) * 0.1).astype(np.float32)
    for _ in range(3):
        sess.run(None, {"audio_waveform": x})
    t = []
    for _ in range(20):
        t0 = time.perf_counter()
        sess.run(None, {"audio_waveform": x})
        t.append((time.perf_counter() - t0) * 1000)
    print(f"Desktop CPU latency per 4 s window: mean {np.mean(t):.1f} ms, p95 {np.percentile(t, 95):.1f} ms")


if __name__ == "__main__":
    main()
