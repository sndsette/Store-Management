-- =====================================================================
--  SUPERMARKET MANAGEMENT - PostgreSQL schema + seed data
--  Run with:  psql -U postgres -d supermarket -f supermarket.sql
--  Currency: DZD (Algerian dinar)
-- =====================================================================

-- Clean re-run (safe in development only)
DROP VIEW  IF EXISTS v_low_stock, v_stock_by_category, v_register_status, v_daily_sales CASCADE;
DROP TABLE IF EXISTS salary_history, cash_movements, sale_items, sales,
                     cash_registers, employees, products, categories CASCADE;

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- for password hashing

-- =====================================================================
-- 1. TABLES
-- =====================================================================

CREATE TABLE categories (
    category_id  SERIAL PRIMARY KEY,
    name         VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE products (
    product_id   SERIAL PRIMARY KEY,
    barcode      VARCHAR(20)  NOT NULL UNIQUE,
    name         VARCHAR(100) NOT NULL,
    category_id  INT NOT NULL REFERENCES categories(category_id)
                     ON UPDATE CASCADE ON DELETE RESTRICT,
    unit_price   NUMERIC(10,2) NOT NULL CHECK (unit_price >= 0),
    stock_qty    INT NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    min_stock    INT NOT NULL DEFAULT 10 CHECK (min_stock >= 0),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE   -- "delete" = soft delete
);
CREATE INDEX idx_products_name     ON products (LOWER(name));
CREATE INDEX idx_products_category ON products (category_id);

CREATE TABLE employees (
    employee_id    SERIAL PRIMARY KEY,
    first_name     VARCHAR(50) NOT NULL,
    last_name      VARCHAR(50) NOT NULL,
    username       VARCHAR(30) NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,
    role           VARCHAR(10) NOT NULL CHECK (role IN ('cashier','manager')),
    salary         NUMERIC(10,2) NOT NULL CHECK (salary >= 0),
    hired_at       DATE NOT NULL DEFAULT CURRENT_DATE,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE cash_registers (
    register_id  SERIAL PRIMARY KEY,
    label        VARCHAR(30) NOT NULL UNIQUE,
    balance      NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    status       VARCHAR(10) NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed'))
);

CREATE TABLE sales (
    sale_id       SERIAL PRIMARY KEY,
    register_id   INT NOT NULL REFERENCES cash_registers(register_id),
    employee_id   INT NOT NULL REFERENCES employees(employee_id),
    sale_date     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status        VARCHAR(12) NOT NULL DEFAULT 'in_progress'
                      CHECK (status IN ('in_progress','completed','cancelled')),
    total_amount  NUMERIC(12,2) NOT NULL DEFAULT 0
);
CREATE INDEX idx_sales_date ON sales (sale_date);

CREATE TABLE sale_items (
    item_id     SERIAL PRIMARY KEY,
    sale_id     INT NOT NULL REFERENCES sales(sale_id) ON DELETE CASCADE,
    product_id  INT NOT NULL REFERENCES products(product_id),
    quantity    INT NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(10,2) NOT NULL CHECK (unit_price >= 0),  -- price at time of sale
    UNIQUE (sale_id, product_id)
);

CREATE TABLE cash_movements (
    movement_id    SERIAL PRIMARY KEY,
    register_id    INT NOT NULL REFERENCES cash_registers(register_id),
    employee_id    INT REFERENCES employees(employee_id),
    sale_id        INT REFERENCES sales(sale_id),
    movement_type  VARCHAR(20) NOT NULL
                       CHECK (movement_type IN ('sale','change_given','change_taken','adjustment')),
    amount         NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE salary_history (
    history_id   SERIAL PRIMARY KEY,
    employee_id  INT NOT NULL REFERENCES employees(employee_id),
    old_salary   NUMERIC(10,2) NOT NULL,
    new_salary   NUMERIC(10,2) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =====================================================================
-- 2. TRIGGERS
-- =====================================================================

-- 2a. Keep sales.total_amount in sync with sale_items
CREATE OR REPLACE FUNCTION refresh_sale_total() RETURNS TRIGGER AS $$
DECLARE v_sale INT;
BEGIN
    v_sale := COALESCE(NEW.sale_id, OLD.sale_id);
    UPDATE sales
       SET total_amount = COALESCE((SELECT SUM(quantity * unit_price)
                                      FROM sale_items WHERE sale_id = v_sale), 0)
     WHERE sale_id = v_sale;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sale_items_total
AFTER INSERT OR UPDATE OR DELETE ON sale_items
FOR EACH ROW EXECUTE FUNCTION refresh_sale_total();

-- 2b. Log every salary change automatically
CREATE OR REPLACE FUNCTION log_salary_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.salary <> OLD.salary THEN
        INSERT INTO salary_history (employee_id, old_salary, new_salary)
        VALUES (OLD.employee_id, OLD.salary, NEW.salary);
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_salary_history
AFTER UPDATE OF salary ON employees
FOR EACH ROW EXECUTE FUNCTION log_salary_change();

-- =====================================================================
-- 3. BUSINESS FUNCTIONS (called by the server)
-- =====================================================================

-- Validate a sale: decrease stock, add money to the till, log movement.
-- Everything runs in ONE transaction: if stock is insufficient, all is rolled back.
CREATE OR REPLACE FUNCTION complete_sale(p_sale_id INT) RETURNS NUMERIC AS $$
DECLARE
    v_status TEXT; v_reg INT; v_emp INT; v_total NUMERIC;
BEGIN
    SELECT status, register_id, employee_id
      INTO v_status, v_reg, v_emp
      FROM sales WHERE sale_id = p_sale_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Sale % does not exist', p_sale_id;
    END IF;
    IF v_status <> 'in_progress' THEN
        RAISE EXCEPTION 'Sale % is already %', p_sale_id, v_status;
    END IF;

    -- CHECK (stock_qty >= 0) makes this fail if stock is not enough
    UPDATE products p
       SET stock_qty = p.stock_qty - si.quantity
      FROM sale_items si
     WHERE si.sale_id = p_sale_id AND si.product_id = p.product_id;

    SELECT COALESCE(SUM(quantity * unit_price), 0) INTO v_total
      FROM sale_items WHERE sale_id = p_sale_id;

    UPDATE sales SET status = 'completed', total_amount = v_total
     WHERE sale_id = p_sale_id;

    UPDATE cash_registers SET balance = balance + v_total
     WHERE register_id = v_reg;

    IF v_total > 0 THEN
        INSERT INTO cash_movements (register_id, employee_id, sale_id, movement_type, amount)
        VALUES (v_reg, v_emp, p_sale_id, 'sale', v_total);
    END IF;

    RETURN v_total;
END $$ LANGUAGE plpgsql;

-- Cashier: give change (money leaves the till) or take change (money enters)
CREATE OR REPLACE FUNCTION register_change(
    p_register INT, p_employee INT, p_type TEXT, p_amount NUMERIC
) RETURNS NUMERIC AS $$
DECLARE v_balance NUMERIC;
BEGIN
    IF p_type NOT IN ('change_given','change_taken') THEN
        RAISE EXCEPTION 'Type must be change_given or change_taken';
    END IF;

    IF p_type = 'change_given' THEN
        UPDATE cash_registers SET balance = balance - p_amount
         WHERE register_id = p_register RETURNING balance INTO v_balance;
    ELSE
        UPDATE cash_registers SET balance = balance + p_amount
         WHERE register_id = p_register RETURNING balance INTO v_balance;
    END IF;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Register % does not exist', p_register;
    END IF;

    INSERT INTO cash_movements (register_id, employee_id, movement_type, amount)
    VALUES (p_register, p_employee, p_type, p_amount);

    RETURN v_balance;
END $$ LANGUAGE plpgsql;

-- Login check (password stored hashed with bcrypt)
CREATE OR REPLACE FUNCTION check_login(p_user TEXT, p_pass TEXT)
RETURNS TABLE (employee_id INT, full_name TEXT, role TEXT) AS $$
    SELECT e.employee_id, e.first_name || ' ' || e.last_name, e.role::TEXT
      FROM employees e
     WHERE e.username = p_user
       AND e.is_active
       AND e.password_hash = crypt(p_pass, e.password_hash);
$$ LANGUAGE sql;

-- =====================================================================
-- 4. VIEWS (ready-made queries for the manager application)
-- =====================================================================

CREATE VIEW v_stock_by_category AS
SELECT c.name AS category,
       COUNT(p.product_id)             AS nb_products,
       SUM(p.stock_qty)                AS total_units,
       SUM(p.stock_qty * p.unit_price) AS stock_value
  FROM categories c
  LEFT JOIN products p ON p.category_id = c.category_id AND p.is_active
 GROUP BY c.name
 ORDER BY c.name;

CREATE VIEW v_low_stock AS
SELECT p.product_id, p.barcode, p.name, c.name AS category,
       p.stock_qty, p.min_stock
  FROM products p JOIN categories c USING (category_id)
 WHERE p.is_active AND p.stock_qty <= p.min_stock
 ORDER BY p.stock_qty;

CREATE VIEW v_register_status AS
SELECT r.register_id, r.label, r.status, r.balance,
       COALESCE(SUM(m.amount) FILTER (WHERE m.movement_type = 'sale'
                 AND m.created_at::date = CURRENT_DATE), 0) AS sales_today
  FROM cash_registers r
  LEFT JOIN cash_movements m USING (register_id)
 GROUP BY r.register_id;

CREATE VIEW v_daily_sales AS
SELECT sale_date::date AS day, COUNT(*) AS nb_sales, SUM(total_amount) AS revenue
  FROM sales WHERE status = 'completed'
 GROUP BY sale_date::date
 ORDER BY day DESC;

-- =====================================================================
-- 5. SEED DATA
-- =====================================================================

INSERT INTO categories (name) VALUES
 ('Dairy & Eggs'),
 ('Bakery'),
 ('Beverages'),
 ('Fruits & Vegetables'),
 ('Grocery'),
 ('Cleaning & Hygiene');

-- 30 products: 5 per category (price in DZD)
INSERT INTO products (barcode, name, category_id, unit_price, stock_qty, min_stock) VALUES
-- 1 Dairy & Eggs
('6130000000011','Fresh Milk 1L',            1,  110.00, 120, 30),
('6130000000012','Yogurt Plain 125g',        1,   35.00, 200, 50),
('6130000000013','Butter 250g',              1,  420.00,  60, 15),
('6130000000014','Eggs Tray (30)',           1,  780.00,  45, 10),
('6130000000015','Camembert Cheese 250g',    1,  390.00,  50, 12),
-- 2 Bakery
('6130000000021','Baguette',                 2,   15.00, 300, 80),
('6130000000022','Croissant',                2,   40.00, 150, 40),
('6130000000023','Sliced Bread 500g',        2,  120.00,  90, 25),
('6130000000024','Traditional Semolina Bread',2,  30.00, 140, 40),
('6130000000025','Chocolate Cake Slice',     2,  150.00,  35, 10),
-- 3 Beverages
('6130000000031','Mineral Water 1.5L',       3,   40.00, 400, 100),
('6130000000032','Orange Juice 1L',          3,  190.00,  80, 20),
('6130000000033','Cola 2L',                  3,  170.00, 150, 40),
('6130000000034','Green Tea Box (25)',       3,  260.00,  70, 15),
('6130000000035','Ground Coffee 250g',       3,  480.00,  55, 15),
-- 4 Fruits & Vegetables
('6130000000041','Potatoes 1kg',             4,   70.00, 250, 60),
('6130000000042','Tomatoes 1kg',             4,  120.00, 180, 50),
('6130000000043','Bananas 1kg',              4,  350.00,  90, 25),
('6130000000044','Apples 1kg',               4,  280.00, 100, 25),
('6130000000045','Onions 1kg',               4,   60.00, 220, 60),
-- 5 Grocery
('6130000000051','White Rice 1kg',           5,  180.00, 160, 40),
('6130000000052','Spaghetti 500g',           5,   75.00, 260, 60),
('6130000000053','Sunflower Oil 1L',         5,  240.00, 140, 35),
('6130000000054','Sugar 1kg',                5,  110.00, 200, 50),
('6130000000055','Tomato Paste 400g',        5,  130.00, 130, 30),
-- 6 Cleaning & Hygiene
('6130000000061','Dish Soap 750ml',          6,  210.00,  75, 20),
('6130000000062','Laundry Detergent 3kg',    6,  950.00,  40, 10),
('6130000000063','Toilet Paper (6 rolls)',   6,  320.00,  95, 25),
('6130000000064','Shampoo 400ml',            6,  450.00,  60, 15),
('6130000000065','Toothpaste 100ml',         6,  190.00,  85, 20);

-- Employees (passwords are hashed). DEMO passwords: manager123 / cashier123
INSERT INTO employees (first_name, last_name, username, password_hash, role, salary, hired_at) VALUES
 ('Karim',  'Benali',  'manager1', crypt('manager123', gen_salt('bf')), 'manager', 90000, '2023-01-10'),
 ('Sarah',  'Haddad',  'cashier1', crypt('cashier123', gen_salt('bf')), 'cashier', 42000, '2024-03-01'),
 ('Yacine', 'Meziane', 'cashier2', crypt('cashier123', gen_salt('bf')), 'cashier', 40000, '2024-06-15'),
 ('Lina',   'Boudiaf', 'cashier3', crypt('cashier123', gen_salt('bf')), 'cashier', 41000, '2025-01-20');

INSERT INTO cash_registers (label, balance, status) VALUES
 ('Register 1', 20000, 'open'),
 ('Register 2', 20000, 'open'),
 ('Register 3', 15000, 'closed');

-- Demo sale so the manager views are not empty
INSERT INTO sales (register_id, employee_id) VALUES (1, 2);
INSERT INTO sale_items (sale_id, product_id, quantity, unit_price)
SELECT 1, product_id, q, unit_price
  FROM (VALUES (1,2),(6,3),(11,1)) AS v(pid,q)
  JOIN products ON product_id = v.pid;
SELECT complete_sale(1);

-- =====================================================================
-- 6. QUICK TESTS (uncomment to try)
-- =====================================================================
-- SELECT * FROM v_stock_by_category;
-- SELECT * FROM v_low_stock;
-- SELECT * FROM v_register_status;
-- SELECT * FROM check_login('cashier1','cashier123');
-- SELECT register_change(1, 2, 'change_given', 500);
