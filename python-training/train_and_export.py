import json
import os
import random
import shutil

import numpy as np
import torch
import torch.nn.functional as F

from data import MIN_POSITIVE_RATING, build_batches, build_training_state, load_rating_interactions
from model import TwoTower


SEED = 42
random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)

EMBED_DIM = 16
EPOCHS = 300
LR = 1e-2


def train(samples, user_vocab, item_vocab):
    model = TwoTower(
        n_users=len(user_vocab),
        n_items=len(item_vocab),
        embed_dim=EMBED_DIM,
    )
    optimizer = torch.optim.Adam(model.parameters(), lr=LR)

    model.train()
    for epoch in range(EPOCHS):
        total_loss = 0.0
        num_batches = 0
        random.shuffle(samples)
        for user_ids, item_ids in build_batches(samples, user_vocab, item_vocab):
            optimizer.zero_grad()
            user_vecs, item_vecs = model(user_ids, item_ids)
            logits = user_vecs @ item_vecs.T
            labels = torch.arange(logits.shape[0], dtype=torch.long)
            loss = F.cross_entropy(logits, labels)
            loss.backward()
            optimizer.step()
            total_loss += loss.item()
            num_batches += 1

        if (epoch + 1) % 50 == 0:
            avg_loss = total_loss / max(1, num_batches)
            print(f"epoch={epoch+1} avg_loss={avg_loss:.4f}")

    return model


def export_artifacts(model, samples, users, items, user_vocab, item_vocab):
    script_dir = os.path.dirname(os.path.abspath(__file__))
    artifact_dir = os.path.join(script_dir, "artifacts")
    java_model_dir = os.path.abspath(os.path.join(script_dir, "..", "src", "main", "resources", "model"))
    os.makedirs(artifact_dir, exist_ok=True)
    os.makedirs(java_model_dir, exist_ok=True)

    model.eval()

    # Export ONNX user tower
    dummy_user = torch.tensor([1], dtype=torch.long)
    onnx_path = os.path.join(artifact_dir, "user_tower.onnx")
    torch.onnx.export(
        model.user_tower,
        (dummy_user,),
        onnx_path,
        input_names=["user_id"],
        output_names=["user_embedding"],
        dynamic_axes=None,
        opset_version=17,
    )

    # Export feature config
    feature_config = {
        "model_version": "demo-two-tower-ratings-v1",
        "embedding_dim": EMBED_DIM,
        "training_data": "src/main/java/com/recsys/data/ratings.txt",
        "min_positive_rating": MIN_POSITIVE_RATING,
        "user_vocab": user_vocab,
        "item_vocab": item_vocab,
    }
    with open(os.path.join(artifact_dir, "feature_config.json"), "w", encoding="utf-8") as f:
        json.dump(feature_config, f, indent=2)

    # Batch-compute all item embeddings in a single forward pass
    with torch.no_grad():
        all_indices = torch.tensor([item_vocab[item] for item in items], dtype=torch.long)
        all_embeddings = model.item_tower(all_indices).cpu().numpy()  # already float32
    item_embeddings = {item: all_embeddings[i].tolist() for i, item in enumerate(items)}

    with open(os.path.join(artifact_dir, "item_embeddings.json"), "w", encoding="utf-8") as f:
        json.dump(item_embeddings, f, indent=2)

    maybe_export_faiss_index(item_embeddings, artifact_dir)

    with open(os.path.join(artifact_dir, "metadata.json"), "w", encoding="utf-8") as f:
        json.dump({
            "training_data": "src/main/java/com/recsys/data/ratings.txt",
            "num_positive_samples": len(samples),
            "num_users": len(users),
            "num_items": len(items),
            "user_tower_inputs": ["user_id"],
            "item_tower_inputs": ["item_id"],
        }, f, indent=2)

    # Copy artifacts into Java resources. FAISS artifacts are optional.
    artifact_names = [
        "user_tower.onnx",
        "feature_config.json",
        "item_embeddings.json",
        "metadata.json",
        "item_embeddings.faiss",
        "item_ids.json",
    ]
    for name in artifact_names:
        src = os.path.join(artifact_dir, name)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(java_model_dir, name))

    print(f"Artifacts written to: {artifact_dir}")
    print(f"Artifacts copied to: {java_model_dir}")


def maybe_export_faiss_index(item_embeddings, artifact_dir):
    try:
        import faiss
    except ImportError:
        print("FAISS not installed; skipping item_embeddings.faiss export.")
        print("Install with: .venv/bin/pip install faiss-cpu")
        return

    item_ids = list(item_embeddings.keys())
    matrix = np.asarray([item_embeddings[item_id] for item_id in item_ids], dtype="float32")
    faiss.normalize_L2(matrix)

    index = faiss.IndexFlatIP(matrix.shape[1])
    index.add(matrix)

    faiss.write_index(index, os.path.join(artifact_dir, "item_embeddings.faiss"))
    with open(os.path.join(artifact_dir, "item_ids.json"), "w", encoding="utf-8") as f:
        json.dump(item_ids, f, indent=2)


if __name__ == "__main__":
    samples = load_rating_interactions()
    users, items, user_vocab, item_vocab = build_training_state(samples)
    print(f"loaded {len(samples)} positive ratings for {len(users)} users and {len(items)} items")
    model = train(samples, user_vocab, item_vocab)
    export_artifacts(model, samples, users, items, user_vocab, item_vocab)
