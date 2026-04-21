import torch.nn as nn
import torch.nn.functional as F


class UserTower(nn.Module):
    def __init__(self, n_users, embed_dim):
        super().__init__()
        self.user_emb = nn.Embedding(n_users, 8)
        self.fc1 = nn.Linear(8, 32)
        self.fc2 = nn.Linear(32, embed_dim)

    def forward(self, user_id):
        x = self.user_emb(user_id)
        x = F.relu(self.fc1(x))
        x = self.fc2(x)
        return F.normalize(x, p=2, dim=1)


class ItemTower(nn.Module):
    def __init__(self, n_items, embed_dim):
        super().__init__()
        self.item_emb = nn.Embedding(n_items, 16)
        self.fc = nn.Linear(16, embed_dim)

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
