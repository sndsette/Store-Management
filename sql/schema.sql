-- Run this whole file in the Neon SQL Editor.
-- It is re-runnable: it drops the tables first (all data is reset!).
CREATE EXTENSION IF NOT EXISTS pgcrypto;
DROP TABLE IF EXISTS ligne_vente, vente, produit, utilisateur CASCADE;

CREATE TABLE utilisateur (
    id           SERIAL PRIMARY KEY,
    nom          VARCHAR(100) NOT NULL,
    login        VARCHAR(50)  NOT NULL UNIQUE,
    mot_de_passe VARCHAR(100) NOT NULL,           -- bcrypt hash
    role         VARCHAR(10)  NOT NULL CHECK (role IN ('caissier','gerant'))
);

CREATE TABLE produit (
    id             SERIAL PRIMARY KEY,
    code_barre     VARCHAR(50)  NOT NULL UNIQUE,
    nom            VARCHAR(150) NOT NULL,
    prix           NUMERIC(10,2) NOT NULL CHECK (prix > 0),
    quantite_stock INT NOT NULL DEFAULT 0 CHECK (quantite_stock >= 0),
    seuil_alerte   INT NOT NULL DEFAULT 5  CHECK (seuil_alerte >= 0),
    date_expiration DATE,
    categorie      VARCHAR(80)
);

CREATE TABLE vente (
    id            SERIAL PRIMARY KEY,
    date_vente    TIMESTAMP NOT NULL DEFAULT NOW(),
    id_caissier   INT NOT NULL REFERENCES utilisateur(id),
    total         NUMERIC(10,2) NOT NULL,
    montant_recu  NUMERIC(10,2) NOT NULL,
    monnaie_rendue NUMERIC(10,2) NOT NULL
);

CREATE TABLE ligne_vente (
    id            SERIAL PRIMARY KEY,
    id_vente      INT NOT NULL REFERENCES vente(id) ON DELETE CASCADE,
    id_produit    INT NOT NULL REFERENCES produit(id),
    quantite      INT NOT NULL CHECK (quantite > 0),
    prix_unitaire NUMERIC(10,2) NOT NULL
);

-- Test accounts (password = 1234). crypt/bf gives $2a$ hashes, compatible with jBCrypt.
INSERT INTO utilisateur (nom, login, mot_de_passe, role) VALUES
 ('Gérant',   'gerant',   crypt('1234', gen_salt('bf')), 'gerant'),
 ('Caissier', 'caissier', crypt('1234', gen_salt('bf')), 'caissier');

-- Test products: one expired, some expiring soon, some low stock
INSERT INTO produit (code_barre, nom, prix, quantite_stock, seuil_alerte, date_expiration, categorie) VALUES
 ('6130001','Lait 1L',        95,  50, 10, CURRENT_DATE + 20, 'Laitier'),
 ('6130002','Yaourt nature',  30,   4, 10, CURRENT_DATE + 3,  'Laitier'),
 ('6130003','Fromage',       250,  25,  5, CURRENT_DATE - 2,  'Laitier'),
 ('6130004','Pain',           10, 100, 20, CURRENT_DATE + 1,  'Boulangerie'),
 ('6130005','Huile 5L',      900,   8, 10, CURRENT_DATE + 240,'Épicerie'),
 ('6130006','Eau 1.5L',       35, 200, 50, CURRENT_DATE + 365,'Boissons');
