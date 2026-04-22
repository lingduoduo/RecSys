import torch.nn as nn
import torch.nn.functional as F


class UserTower(nn.Module):
    def __init__(self, n_users, embed_dim, user_emb_dim=8, hidden_dim=32):
        super().__init__()
        self.user_emb = nn.Embedding(n_users, user_emb_dim)
        self.fc1 = nn.Linear(user_emb_dim, hidden_dim)
        self.fc2 = nn.Linear(hidden_dim, embed_dim)

    def forward(self, user_id):
        x = self.user_emb(user_id)
        x = F.relu(self.fc1(x))
        x = self.fc2(x)
        return F.normalize(x, p=2, dim=1)


class ItemTower(nn.Module):
    def __init__(self, n_items, embed_dim, item_emb_dim=16):
        super().__init__()
        self.item_emb = nn.Embedding(n_items, item_emb_dim)
        self.fc = nn.Linear(item_emb_dim, embed_dim)

    def forward(self, item_id):
        x = self.item_emb(item_id)
        x = self.fc(x)
        return F.normalize(x, p=2, dim=1)


class TwoTower(nn.Module):
    def __init__(self, n_users, n_items, embed_dim):
        super().__init__()
        self.user_tower = UserTower(n_users, embed_dim)
        self.item_tower = ItemTower(n_items, embed_dim)

    def forward(self, user_id, item_id):
        return self.user_tower(user_id), self.item_tower(item_id)
