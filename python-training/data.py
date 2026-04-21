import csv
import os
import random

import torch


MIN_POSITIVE_RATING = 3.5


def build_vocab(values):
    vocab = {"__UNK__": 0}
    for i, v in enumerate(values, start=1):
        vocab[v] = i
    return vocab


def numeric_string_key(value):
    return (0, int(value)) if value.isdigit() else (1, value)


def load_rating_interactions(min_rating=MIN_POSITIVE_RATING):
    script_dir = os.path.dirname(os.path.abspath(__file__))
    ratings_path = os.path.abspath(
        os.path.join(script_dir, "..", "src", "main", "java", "com", "recsys", "data", "ratings.txt")
    )
    if not os.path.exists(ratings_path):
        raise FileNotFoundError(f"ratings.txt not found at {ratings_path}")

    samples = []
    with open(ratings_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rating = float(row["rating"])
            if rating < min_rating:
                continue
            samples.append({
                "user_id": row["userId"].strip(),
                "item_id": row["movieId"].strip(),
                "rating": rating,
                "timestamp": int(row["timestamp"]),
            })

    if not samples:
        raise ValueError(f"No ratings >= {min_rating} found in {ratings_path}")

    random.shuffle(samples)
    return samples


def build_training_state(samples):
    users = sorted({s["user_id"] for s in samples}, key=numeric_string_key)
    items = sorted({s["item_id"] for s in samples}, key=numeric_string_key)
    return users, items, build_vocab(users), build_vocab(items)


def build_batches(samples, user_vocab, item_vocab, batch_size=16):
    encoded = [(user_vocab.get(s["user_id"], 0), item_vocab.get(s["item_id"], 0)) for s in samples]
    for i in range(0, len(encoded), batch_size):
        batch = encoded[i:i + batch_size]
        users, items = zip(*batch)
        yield (
            torch.tensor(users, dtype=torch.long),
            torch.tensor(items, dtype=torch.long),
        )
