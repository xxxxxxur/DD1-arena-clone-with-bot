"""Fit the Java DeepSets residual with NumPy. Paths are relative to the project root."""
import argparse
import os
import struct
import time
from pathlib import Path

os.environ["OPENBLAS_NUM_THREADS"] = "1"
import numpy as np

D, H, G = 39, 24, 6
SHAPES = [(D, H), (H,), (3 * H + G, H), (H,), (H,)]


def dataset(dirs, td_lambda=None):
    paths = [p for directory in dirs for p in sorted(Path(directory).glob("*.dat"))]
    games = []
    for path in paths:
        rows = np.fromfile(path, ">f4").reshape(-1, 1 + 8 * D + G).astype("f4")
        if td_lambda is not None and len(rows):
            # Each shard is chronological, in a fixed team-zero perspective. Original
            # labels encode .7*outcome + .3*teacher and the outcome intervals are disjoint.
            outcome = float(np.round(rows[:, 0].mean()))
            teacher = np.clip((rows[:, 0] - .7 * outcome) / .3, 0, 1)
            future = outcome
            for i in reversed(range(len(rows))):
                rows[i, 0] = .4 * outcome + .6 * future
                future = (1 - td_lambda) * teacher[i] + td_lambda * future
        games.append(rows)
    rows = np.concatenate(games)
    print(f"games={len(paths)} positions={len(rows)}", flush=True)
    return rows


def forward(rows, weights):
    enc, eb, head, hb, out = weights
    x = rows[:, 1:-G].reshape(-1, 2, 4, D)
    g = rows[:, -G:]
    h = np.maximum(x @ enc + eb, 0) * x[..., :1]
    sums = h.sum(axis=2)
    delta = sums[:, 0] - sums[:, 1]
    peak = h.max(axis=2)
    reverse = g[:, [0, 1, 3, 2, 4, 5]].copy()
    reverse[:, [0, 1, 5]] *= -1
    both = np.stack((np.concatenate((delta, peak[:, 0], peak[:, 1], g), axis=1),
                     np.concatenate((-delta, peak[:, 1], peak[:, 0], reverse), axis=1)), axis=1)
    hidden = np.maximum(both @ head + hb, 0)
    bases = (.9*x[..., 0] + .75*x[..., 1] - .5*x[..., 5]).sum(axis=2)
    logits = bases[:, 0] - bases[:, 1] + .5 * ((hidden[:, 0] - hidden[:, 1]) @ out)
    return logits, (x, h, both, hidden)


def gradients(rows, weights):
    logits, (x, h, both, hidden) = forward(rows, weights)
    residual = (.5 / len(rows)) * (1 / (1 + np.exp(-np.clip(logits, -60, 60))) - rows[:, 0])
    dy = np.stack((residual, -residual), axis=1)
    dout = (dy[..., None] * hidden).sum(axis=(0, 1))
    dh = dy[..., None] * weights[-1] * (hidden > 0)
    dhead = both.reshape(-1, both.shape[-1]).T @ dh.reshape(-1, H)
    dp = dh @ weights[2].T
    ds = dp[:, 0, :H] - dp[:, 1, :H]
    dsum = np.stack((ds, -ds), axis=1)
    dpeak = dp[:, :, H:2*H] + dp[:, ::-1, 2*H:3*H]
    mask = h == h.max(axis=2, keepdims=True)
    du = (dsum[:, :, None] + mask * (dpeak / mask.sum(axis=2))[:, :, None]) * (h > 0)
    denc = x.reshape(-1, D).T @ du.reshape(-1, H)
    return [denc, du.sum(axis=(0, 1, 2)), dhead, dh.sum(axis=(0, 1)), dout]


def loss(rows, weights):
    losses = []
    for start in range(0, len(rows), 256):
        batch = rows[start:start+256]
        z, _ = forward(batch, weights)
        losses.append(np.logaddexp(0, z) - batch[:, 0] * z)
    return np.concatenate(losses).mean()


def fit(args):
    rng = np.random.default_rng(912817)
    train, valid = dataset(args.train, args.td_lambda), dataset([args.valid])
    weights = [rng.normal(0, .12, shape).astype("f4") for shape in SHAPES]
    m, v = ([np.zeros_like(w) for w in weights] for _ in range(2))
    best, step = float("inf"), 0
    start = time.monotonic()
    for epoch in range(args.epochs):
        order = rng.permutation(len(train))
        for i in range(0, len(train), 128):
            step += 1
            grads = gradients(train[order[i:i+128]], weights)
            norm = np.sqrt(sum((grad**2).sum() for grad in grads))
            for j, grad in enumerate(grads):
                grad *= min(1, 2 / max(norm, 1e-9))
                m[j] = .9 * m[j] + .1 * grad
                v[j] = .999 * v[j] + .001 * grad**2
                rate = .0015 / np.sqrt(1 + epoch / 4)
                weights[j] -= rate * (m[j] / (1 - .9**step) /
                                      (np.sqrt(v[j] / (1 - .999**step)) + 1e-8) + .0002 * weights[j])
        score = loss(valid, weights)
        if score < best:
            best = score
            temp = args.output.with_suffix(".tmp")
            with temp.open("wb") as f:
                f.write(struct.pack(">I", 0x44535631))
                for w in weights:
                    f.write(w.astype(">f4").tobytes())
            temp.replace(args.output)
        print(f"epoch={epoch+1} valid={score:.6f} best={best:.6f} elapsed_s={time.monotonic()-start:.1f}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", nargs="+", required=True)
    parser.add_argument("--valid", required=True)
    parser.add_argument("--epochs", type=int, default=16)
    parser.add_argument("--td-lambda", type=float, choices=[.7, .8, .9])
    parser.add_argument("--output", required=True, type=Path)
    fit(parser.parse_args())
