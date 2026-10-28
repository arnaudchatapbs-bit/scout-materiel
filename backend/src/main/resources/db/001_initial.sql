-- Schéma initial. Dates en texte ISO-8601 UTC (Instant.toString()).

CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    email TEXT NOT NULL UNIQUE COLLATE NOCASE,
    first_name TEXT NOT NULL DEFAULT '',
    last_name TEXT NOT NULL DEFAULT '',
    -- Vide tant que l'invité n'a pas choisi son mot de passe (lien d'invitation)
    password_hash TEXT NOT NULL DEFAULT '',
    is_admin INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    date_joined TEXT NOT NULL,
    last_login TEXT
);

CREATE TABLE sessions (
    -- SHA-256 du jeton du cookie : une fuite de la base ne donne pas de session valide
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TEXT NOT NULL
);

CREATE TABLE categories (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id INTEGER REFERENCES categories(id) ON DELETE CASCADE,
    is_consumable INTEGER NOT NULL DEFAULT 0
);
-- Unicité du nom parmi les frères, y compris à la racine (parent NULL)
CREATE UNIQUE INDEX categories_unique_name ON categories(COALESCE(parent_id, 0), name COLLATE NOCASE);

CREATE TABLE items (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    category_id INTEGER NOT NULL REFERENCES categories(id),
    reference TEXT UNIQUE COLLATE NOCASE,
    quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity >= 0),
    location TEXT NOT NULL DEFAULT '',
    comment TEXT NOT NULL DEFAULT '',
    remark TEXT NOT NULL DEFAULT '',
    is_consumable INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX items_category ON items(category_id);

-- Panier : conservé côté serveur (même panier sur le téléphone et l'ordinateur)
CREATE TABLE cart_items (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL CHECK (quantity >= 1),
    PRIMARY KEY (user_id, item_id)
);

CREATE TABLE requests (
    id INTEGER PRIMARY KEY,
    requester_id INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE request_lines (
    id INTEGER PRIMARY KEY,
    request_id INTEGER NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    item_id INTEGER NOT NULL REFERENCES items(id),
    quantity INTEGER NOT NULL CHECK (quantity >= 1),
    -- en_attente, reservee, refusee, annulee, rendue
    status TEXT NOT NULL DEFAULT 'en_attente',
    admin_comment TEXT NOT NULL DEFAULT '',
    reserved_at TEXT,
    returned_at TEXT,
    UNIQUE (request_id, item_id)
);
CREATE INDEX request_lines_status_item ON request_lines(status, item_id);
