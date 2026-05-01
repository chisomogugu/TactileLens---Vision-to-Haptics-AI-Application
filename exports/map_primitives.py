"""
Stage 3: Rule-based mapping from 4 haptic dimensions -> 8 primitive weights.

Inputs:  rough, hard, friction, density  — each in [0, 1]
Outputs: dict of 8 Android haptic primitive weights summing to 1.0

Intuition behind each primitive:
  TICK        — rough + hard + dense surface (cobblestone, gravel)
  LOW_TICK    — rough + soft + dense (sand, coarse fabric)
  CLICK       — hard + dense + smooth (polished tile, fine metal mesh)
  THUD        — soft + sparse (foam, thick rubber)
  SLOW_RISE   — smooth + soft + grippy (velvet, neoprene)
  QUICK_RISE  — hard + smooth (glass, polished wood)
  QUICK_FALL  — slippery + smooth (glass, ice, wet plastic)
  SPIN        — fixed at 0.05 (cannot be inferred from a still image)
"""


def predict_primitives(dims):
    """
    Args:
        dims: [rough, hard, friction, density]  all in [0, 1]
    Returns:
        dict mapping primitive name -> weight, weights sum to 1.0
    """
    r, h, fr, d = dims

    raw = {
        "TICK":       r * h * d,
        "LOW_TICK":   r * (1 - h) * d,
        "CLICK":      h * d * (1 - r),
        "THUD":       (1 - h) * (1 - d),
        "SLOW_RISE":  (1 - r) * (1 - h) * fr,
        "QUICK_RISE": h * (1 - r),
        "QUICK_FALL": (1 - fr) * (1 - r),
    }

    total = sum(raw.values()) + 1e-6
    weights = {k: round(v / total * 0.95, 4) for k, v in raw.items()}
    weights["SPIN"] = 0.05
    return weights


def dominant(weights, n=3):
    """Return the top-n primitives by weight."""
    ranked = sorted(
        [(k, v) for k, v in weights.items() if k != "SPIN"],
        key=lambda x: x[1],
        reverse=True,
    )
    return ranked[:n]


if __name__ == "__main__":
    tests = {
        "rock":  [0.82, 0.95, 0.70, 0.33],
        "glass": [0.05, 0.95, 0.08, 0.05],
        "sand":  [0.57, 0.23, 0.63, 0.89],
        "cloth": [0.35, 0.10, 0.73, 0.61],
        "wood":  [0.31, 0.78, 0.43, 0.27],
    }

    print(f"{'':8s}  {'dominant primitives':40s}")
    print("-" * 60)
    for name, dims in tests.items():
        w = predict_primitives(dims)
        top = dominant(w, 3)
        top_str = "  ".join(f"{p}={v:.3f}" for p, v in top)
        print(f"{name:8s}  {top_str}")
