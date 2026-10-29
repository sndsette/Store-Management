"""
Supermarket server - Flask + PostgreSQL (REST / JSON)

Run (development):   python app.py
Run (multi-client):  waitress-serve --threads=16 --port=5000 app:app

The server is the ONLY part that talks to the database.
Every request except /api/login needs the header:  Authorization: Bearer <token>
"""
import os
import secrets
import threading
import time
from contextlib import contextmanager
from decimal import Decimal
from functools import wraps

import psycopg2
from flask import Flask, g, jsonify, request
from flask.json.provider import DefaultJSONProvider
from psycopg2 import errors
from psycopg2.extras import RealDictCursor
from psycopg2.pool import ThreadedConnectionPool

# ---------------------------------------------------------------------
# Configuration (override with environment variables)
# ---------------------------------------------------------------------
DB_CONFIG = dict(
    host=os.getenv("DB_HOST", "localhost"),
    port=int(os.getenv("DB_PORT", "5432")),
    dbname=os.getenv("DB_NAME", "supermarket"),
    user=os.getenv("DB_USER", "postgres"),
    password=os.getenv("DB_PASSWORD", "postgres"),
)
TOKEN_TTL = 8 * 3600  # a login lasts 8 hours


class JSONProvider(DefaultJSONProvider):
    """Send NUMERIC columns as JSON numbers (easier for Java clients)."""

    @staticmethod
    def default(o):
        if isinstance(o, Decimal):
            return float(o)
        return DefaultJSONProvider.default(o)


app = Flask(__name__)
app.json = JSONProvider(app)
pool = ThreadedConnectionPool(minconn=2, maxconn=20, **DB_CONFIG)


# ---------------------------------------------------------------------
# Database helper: one transaction per request-operation
# ---------------------------------------------------------------------
@contextmanager
def db():
    conn = pool.getconn()
    try:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            yield cur
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        pool.putconn(conn)


class ApiError(Exception):
    def __init__(self, message, status=400):
        self.message, self.status = message, status


@app.errorhandler(ApiError)
def handle_api_error(e):
    return jsonify(error=e.message), e.status


@app.errorhandler(errors.CheckViolation)
def handle_check(e):
    text = str(e)
    if "stock_qty" in text:
        return jsonify(error="Not enough stock for one of the products"), 409
    if "balance" in text:
        return jsonify(error="Not enough cash in the register"), 409
    return jsonify(error="Invalid value"), 400


@app.errorhandler(errors.UniqueViolation)
def handle_unique(e):
    return jsonify(error="This value already exists (barcode, product in sale...)"), 409


@app.errorhandler(errors.RaiseException)
def handle_raise(e):  # RAISE EXCEPTION from our SQL functions
    return jsonify(error=e.diag.message_primary), 409


@app.errorhandler(psycopg2.OperationalError)
def handle_db_down(e):
    return jsonify(error="Database unavailable"), 503


@app.errorhandler(404)
def handle_404(e):
    return jsonify(error="Not found"), 404


@app.errorhandler(Exception)
def handle_unexpected(e):
    app.logger.exception(e)
    return jsonify(error="Internal server error"), 500


# ---------------------------------------------------------------------
# Authentication (in-memory tokens)
# ---------------------------------------------------------------------
sessions = {}  # token -> {employee_id, full_name, role, expires}
sessions_lock = threading.Lock()


def require_auth(*roles):
    """Decorator: valid token required, optionally restricted to roles."""

    def decorator(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            header = request.headers.get("Authorization", "")
            token = header[7:] if header.startswith("Bearer ") else ""
            with sessions_lock:
                s = sessions.get(token)
                if s and s["expires"] < time.time():
                    del sessions[token]
                    s = None
            if not s:
                raise ApiError("Not logged in", 401)
            if roles and s["role"] not in roles:
                raise ApiError("Forbidden for your role", 403)
            g.user = s
            return fn(*args, **kwargs)

        return wrapper

    return decorator


def body():
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        raise ApiError("JSON body required")
    return data


def need(data, *fields):
    missing = [f for f in fields if data.get(f) in (None, "")]
    if missing:
        raise ApiError("Missing field(s): " + ", ".join(missing))


def positive_int(value, name):
    try:
        v = int(value)
    except (TypeError, ValueError):
        raise ApiError(f"{name} must be an integer")
    if v <= 0:
        raise ApiError(f"{name} must be > 0")
    return v


def positive_number(value, name, allow_zero=False):
    try:
        v = Decimal(str(value))
    except Exception:
        raise ApiError(f"{name} must be a number")
    if v < 0 or (v == 0 and not allow_zero):
        raise ApiError(f"{name} must be > 0")
    return v


# ---------------------------------------------------------------------
# Auth endpoints
# ---------------------------------------------------------------------
@app.get("/api/health")
def health():
    with db() as cur:
        cur.execute("SELECT 1 AS ok")
    return jsonify(status="ok")


@app.post("/api/login")
def login():
    data = body()
    need(data, "username", "password")
    with db() as cur:
        cur.execute("SELECT * FROM check_login(%s, %s)",
                    (data["username"], data["password"]))
        row = cur.fetchone()
    if not row:
        raise ApiError("Wrong username or password", 401)
    token = secrets.token_hex(24)
    with sessions_lock:
        sessions[token] = dict(employee_id=row["employee_id"],
                               full_name=row["full_name"], role=row["role"],
                               expires=time.time() + TOKEN_TTL)
    return jsonify(token=token, employee_id=row["employee_id"],
                   full_name=row["full_name"], role=row["role"])


@app.post("/api/logout")
@require_auth()
def logout():
    token = request.headers["Authorization"][7:]
    with sessions_lock:
        sessions.pop(token, None)
    return jsonify(message="Logged out")


# ---------------------------------------------------------------------
# Products (both roles can read)
# ---------------------------------------------------------------------
PRODUCT_COLS = """p.product_id, p.barcode, p.name, c.name AS category,
                  p.category_id, p.unit_price, p.stock_qty, p.min_stock"""


@app.get("/api/products/search")
@require_auth()
def search_products():
    q = request.args.get("q", "").strip()
    category = request.args.get("category_id")
    sql = f"""SELECT {PRODUCT_COLS} FROM products p
              JOIN categories c USING (category_id)
              WHERE p.is_active AND (LOWER(p.name) LIKE LOWER(%s) OR p.barcode = %s)"""
    params = [f"%{q}%", q]
    if category:
        sql += " AND p.category_id = %s"
        params.append(positive_int(category, "category_id"))
    sql += " ORDER BY p.name LIMIT 100"
    with db() as cur:
        cur.execute(sql, params)
        return jsonify(cur.fetchall())


@app.get("/api/products/<int:product_id>")
@require_auth()
def get_product(product_id):
    with db() as cur:
        cur.execute(f"""SELECT {PRODUCT_COLS} FROM products p
                        JOIN categories c USING (category_id)
                        WHERE p.product_id = %s AND p.is_active""", (product_id,))
        row = cur.fetchone()
    if not row:
        raise ApiError("Product not found", 404)
    return jsonify(row)


@app.get("/api/categories")
@require_auth()
def list_categories():
    with db() as cur:
        cur.execute("SELECT category_id, name FROM categories ORDER BY name")
        return jsonify(cur.fetchall())


# ---------------------------------------------------------------------
# Cash registers
# ---------------------------------------------------------------------
@app.get("/api/registers")
@require_auth()
def list_registers():
    with db() as cur:
        cur.execute("SELECT register_id, label, status, balance "
                    "FROM cash_registers ORDER BY register_id")
        return jsonify(cur.fetchall())


@app.post("/api/registers/<int:register_id>/change")
@require_auth("cashier", "manager")
def register_change(register_id):
    """Give change (money leaves the till) or take change (money enters)."""
    data = body()
    need(data, "type", "amount")
    if data["type"] not in ("change_given", "change_taken"):
        raise ApiError("type must be change_given or change_taken")
    amount = positive_number(data["amount"], "amount")
    with db() as cur:
        cur.execute("SELECT status FROM cash_registers WHERE register_id=%s FOR UPDATE",
                    (register_id,))
        reg = cur.fetchone()
        if not reg:
            raise ApiError("Register not found", 404)
        if reg["status"] != "open":
            raise ApiError("Register is closed", 409)
        cur.execute("SELECT register_change(%s,%s,%s,%s) AS balance",
                    (register_id, g.user["employee_id"], data["type"], amount))
        return jsonify(balance=cur.fetchone()["balance"])


# ---------------------------------------------------------------------
# Sales (cashier workflow)
# ---------------------------------------------------------------------
def load_sale(cur, sale_id, must_be_in_progress=True, lock=False):
    cur.execute("SELECT * FROM sales WHERE sale_id=%s" + (" FOR UPDATE" if lock else ""),
                (sale_id,))
    sale = cur.fetchone()
    if not sale:
        raise ApiError("Sale not found", 404)
    if g.user["role"] == "cashier" and sale["employee_id"] != g.user["employee_id"]:
        raise ApiError("This sale belongs to another cashier", 403)
    if must_be_in_progress and sale["status"] != "in_progress":
        raise ApiError(f"Sale is already {sale['status']}", 409)
    return sale


def sale_with_items(cur, sale_id):
    cur.execute("SELECT * FROM sales WHERE sale_id=%s", (sale_id,))
    sale = cur.fetchone()
    cur.execute("""SELECT si.item_id, si.product_id, p.name, p.barcode, si.quantity,
                          si.unit_price, si.quantity * si.unit_price AS line_total
                     FROM sale_items si JOIN products p USING (product_id)
                    WHERE si.sale_id=%s ORDER BY si.item_id""", (sale_id,))
    sale["items"] = cur.fetchall()
    return sale


@app.post("/api/sales")
@require_auth("cashier", "manager")
def start_sale():
    data = body()
    need(data, "register_id")
    with db() as cur:
        cur.execute("SELECT status FROM cash_registers WHERE register_id=%s",
                    (positive_int(data["register_id"], "register_id"),))
        reg = cur.fetchone()
        if not reg:
            raise ApiError("Register not found", 404)
        if reg["status"] != "open":
            raise ApiError("Register is closed", 409)
        cur.execute("INSERT INTO sales (register_id, employee_id) VALUES (%s,%s) "
                    "RETURNING sale_id", (data["register_id"], g.user["employee_id"]))
        sale_id = cur.fetchone()["sale_id"]
        return jsonify(sale_with_items(cur, sale_id)), 201


@app.get("/api/sales/<int:sale_id>")
@require_auth("cashier", "manager")
def get_sale(sale_id):
    with db() as cur:
        load_sale(cur, sale_id, must_be_in_progress=False)
        return jsonify(sale_with_items(cur, sale_id))


@app.post("/api/sales/<int:sale_id>/items")
@require_auth("cashier", "manager")
def add_item(sale_id):
    """Add a product (by product_id or barcode). Adding twice increases quantity."""
    data = body()
    qty = positive_int(data.get("quantity", 1), "quantity")
    if not data.get("product_id") and not data.get("barcode"):
        raise ApiError("product_id or barcode required")
    with db() as cur:
        load_sale(cur, sale_id, lock=True)
        if data.get("product_id"):
            cur.execute("SELECT * FROM products WHERE product_id=%s AND is_active",
                        (positive_int(data["product_id"], "product_id"),))
        else:
            cur.execute("SELECT * FROM products WHERE barcode=%s AND is_active",
                        (str(data["barcode"]),))
        product = cur.fetchone()
        if not product:
            raise ApiError("Product not found", 404)
        cur.execute("SELECT quantity FROM sale_items WHERE sale_id=%s AND product_id=%s",
                    (sale_id, product["product_id"]))
        existing = cur.fetchone()
        wanted = qty + (existing["quantity"] if existing else 0)
        if wanted > product["stock_qty"]:
            raise ApiError(f"Only {product['stock_qty']} in stock for {product['name']}", 409)
        cur.execute("""INSERT INTO sale_items (sale_id, product_id, quantity, unit_price)
                       VALUES (%s,%s,%s,%s)
                       ON CONFLICT (sale_id, product_id)
                       DO UPDATE SET quantity = sale_items.quantity + EXCLUDED.quantity""",
                    (sale_id, product["product_id"], qty, product["unit_price"]))
        return jsonify(sale_with_items(cur, sale_id))


@app.delete("/api/sales/<int:sale_id>/items/<int:item_id>")
@require_auth("cashier", "manager")
def remove_item(sale_id, item_id):
    with db() as cur:
        load_sale(cur, sale_id, lock=True)
        cur.execute("DELETE FROM sale_items WHERE item_id=%s AND sale_id=%s",
                    (item_id, sale_id))
        if cur.rowcount == 0:
            raise ApiError("Item not found in this sale", 404)
        return jsonify(sale_with_items(cur, sale_id))


@app.put("/api/sales/<int:sale_id>/items/<int:item_id>")
@require_auth("cashier", "manager")
def change_item(sale_id, item_id):
    """Change the quantity and/or replace the product of a line."""
    data = body()
    with db() as cur:
        load_sale(cur, sale_id, lock=True)
        cur.execute("SELECT * FROM sale_items WHERE item_id=%s AND sale_id=%s",
                    (item_id, sale_id))
        item = cur.fetchone()
        if not item:
            raise ApiError("Item not found in this sale", 404)
        product_id = int(data.get("product_id", item["product_id"]))
        qty = positive_int(data.get("quantity", item["quantity"]), "quantity")
        cur.execute("SELECT * FROM products WHERE product_id=%s AND is_active", (product_id,))
        product = cur.fetchone()
        if not product:
            raise ApiError("Product not found", 404)
        if qty > product["stock_qty"]:
            raise ApiError(f"Only {product['stock_qty']} in stock for {product['name']}", 409)
        price = item["unit_price"] if product_id == item["product_id"] else product["unit_price"]
        cur.execute("UPDATE sale_items SET product_id=%s, quantity=%s, unit_price=%s "
                    "WHERE item_id=%s", (product_id, qty, price, item_id))
        return jsonify(sale_with_items(cur, sale_id))


@app.post("/api/sales/<int:sale_id>/complete")
@require_auth("cashier", "manager")
def complete_sale(sale_id):
    with db() as cur:
        load_sale(cur, sale_id)
        cur.execute("SELECT COUNT(*) AS n FROM sale_items WHERE sale_id=%s", (sale_id,))
        if cur.fetchone()["n"] == 0:
            raise ApiError("The sale is empty", 409)
        cur.execute("SELECT complete_sale(%s) AS total", (sale_id,))
        total = cur.fetchone()["total"]
        return jsonify(message="Sale completed", total=total)


@app.post("/api/sales/<int:sale_id>/cancel")
@require_auth("cashier", "manager")
def cancel_sale(sale_id):
    with db() as cur:
        load_sale(cur, sale_id, lock=True)
        cur.execute("UPDATE sales SET status='cancelled' WHERE sale_id=%s", (sale_id,))
        return jsonify(message="Sale cancelled")


# ---------------------------------------------------------------------
# Manager endpoints
# ---------------------------------------------------------------------
@app.get("/api/manager/stock")
@require_auth("manager")
def stock():
    with db() as cur:
        cur.execute("SELECT * FROM v_stock_by_category")
        by_category = cur.fetchall()
        cur.execute(f"""SELECT {PRODUCT_COLS} FROM products p
                        JOIN categories c USING (category_id)
                        WHERE p.is_active ORDER BY c.name, p.name""")
        return jsonify(by_category=by_category, products=cur.fetchall())


@app.get("/api/manager/stock/low")
@require_auth("manager")
def low_stock():
    with db() as cur:
        cur.execute("SELECT * FROM v_low_stock")
        return jsonify(cur.fetchall())


@app.get("/api/manager/registers")
@require_auth("manager")
def registers_status():
    with db() as cur:
        cur.execute("SELECT * FROM v_register_status ORDER BY register_id")
        return jsonify(cur.fetchall())


@app.get("/api/manager/sales/daily")
@require_auth("manager")
def daily_sales():
    with db() as cur:
        cur.execute("SELECT * FROM v_daily_sales LIMIT 30")
        return jsonify(cur.fetchall())


@app.post("/api/manager/products")
@require_auth("manager")
def add_product():
    data = body()
    need(data, "barcode", "name", "category_id", "unit_price")
    price = positive_number(data["unit_price"], "unit_price", allow_zero=True)
    stock_qty = int(data.get("stock_qty", 0))
    min_stock = int(data.get("min_stock", 10))
    if stock_qty < 0 or min_stock < 0:
        raise ApiError("stock_qty and min_stock must be >= 0")
    with db() as cur:
        try:
            cur.execute("""INSERT INTO products
                           (barcode, name, category_id, unit_price, stock_qty, min_stock)
                           VALUES (%s,%s,%s,%s,%s,%s) RETURNING product_id""",
                        (str(data["barcode"]), data["name"].strip(),
                         positive_int(data["category_id"], "category_id"),
                         price, stock_qty, min_stock))
        except errors.ForeignKeyViolation:
            raise ApiError("Unknown category_id", 404)
        return jsonify(product_id=cur.fetchone()["product_id"]), 201


@app.put("/api/manager/products/<int:product_id>")
@require_auth("manager")
def update_product(product_id):
    """Update price / stock / min_stock / name (any subset)."""
    data = body()
    allowed = {"name": str, "unit_price": float, "stock_qty": int, "min_stock": int}
    sets, params = [], []
    for field, cast in allowed.items():
        if field in data:
            try:
                value = cast(data[field])
            except (TypeError, ValueError):
                raise ApiError(f"Invalid {field}")
            sets.append(f"{field}=%s")
            params.append(value)
    if not sets:
        raise ApiError("Nothing to update")
    with db() as cur:
        cur.execute(f"UPDATE products SET {', '.join(sets)} "
                    "WHERE product_id=%s AND is_active", params + [product_id])
        if cur.rowcount == 0:
            raise ApiError("Product not found", 404)
        return jsonify(message="Product updated")


@app.delete("/api/manager/products/<int:product_id>")
@require_auth("manager")
def delete_product(product_id):
    """Soft delete: keeps the sales history intact."""
    with db() as cur:
        cur.execute("UPDATE products SET is_active=FALSE "
                    "WHERE product_id=%s AND is_active", (product_id,))
        if cur.rowcount == 0:
            raise ApiError("Product not found", 404)
        return jsonify(message="Product deleted")


@app.get("/api/manager/cashiers")
@require_auth("manager")
def list_cashiers():
    with db() as cur:
        cur.execute("""SELECT employee_id, first_name, last_name, username,
                              salary, hired_at
                         FROM employees
                        WHERE role='cashier' AND is_active ORDER BY employee_id""")
        return jsonify(cur.fetchall())


@app.put("/api/manager/cashiers/<int:employee_id>/salary")
@require_auth("manager")
def change_salary(employee_id):
    data = body()
    need(data, "salary")
    salary = positive_number(data["salary"], "salary", allow_zero=True)
    with db() as cur:
        cur.execute("UPDATE employees SET salary=%s "
                    "WHERE employee_id=%s AND role='cashier' AND is_active",
                    (salary, employee_id))
        if cur.rowcount == 0:
            raise ApiError("Cashier not found", 404)
        return jsonify(message="Salary updated", salary=salary)


@app.get("/api/manager/cashiers/<int:employee_id>/salary-history")
@require_auth("manager")
def salary_history(employee_id):
    with db() as cur:
        cur.execute("""SELECT old_salary, new_salary, changed_at FROM salary_history
                        WHERE employee_id=%s ORDER BY changed_at DESC""", (employee_id,))
        return jsonify(cur.fetchall())


if __name__ == "__main__":
    # threaded=True: several clients at once. Use waitress for the final demo.
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "5000")), threaded=True)
