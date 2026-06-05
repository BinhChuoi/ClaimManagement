"""
Generate synthetic return_order and return_order_invoice data,
write as Parquet partitioned by year/month, upload to S3.

Tables produced:
  processed/return_orders/year=X/month=XX/data.parquet
  processed/return_order_invoices/year=X/month=XX/data.parquet

Run:
  pip install pyarrow boto3 faker
  python scripts/generate_return_orders.py
"""

import json
import random
import string
import boto3
import pyarrow as pa
import pyarrow.parquet as pq
import io
from datetime import datetime, timedelta
from collections import defaultdict

# ── Config ────────────────────────────────────────────────────────────────────
BUCKET          = "levelupjourney-datalake-930388172688"
PREFIX          = "processed"
REGION          = "us-east-1"
START_YEAR      = 2022
END_YEAR        = 2024
ORDERS_PER_MONTH = 80_000  # ~2.88M total return orders across 3 years
RANDOM_SEED     = 42

random.seed(RANDOM_SEED)

# ── Reference data ────────────────────────────────────────────────────────────
SAP_TYPES = ["YCC1", "YCH1", "YCR1", "YCF1"]
SAP_TYPE_LABELS = {
    "YCC1": "Customer Credit Note",
    "YCH1": "Customer Debit Note",
    "YCR1": "Customer Return",
    "YCF1": "Free of Charge",
}
SAP_REASONS = ["01", "02", "03", "04", "05"]
SAP_REASON_LABELS = {
    "01": "Wrong quantity",
    "02": "Wrong product",
    "03": "Damaged goods",
    "04": "Quality issues",
    "05": "Late delivery",
}
RETURN_TYPES    = ["invoice", "order"]
STATUSES        = ["Success", "Failed", "Completed"]
STATUS_WEIGHTS  = [0.65, 0.10, 0.25]
SHIPPING_METHODS = ["01", "02", "03"]   # Standard, Express, Pick-up
CLAIM_PREFIXES  = ["CLM", "RMA", "CRD"]
ERROR_MESSAGES  = [
    "SAP connection timeout",
    "Customer not found in SAP",
    "Material blocked for returns",
    "Credit limit exceeded",
    "Invalid plant code",
]

def rand_str(prefix, n=8):
    return prefix + "".join(random.choices(string.digits, k=n))

def rand_invoice_number():
    return random.randint(10_000_000, 99_999_999)

def rand_sap_order_number():
    # VBELN: 10-digit SAP order number
    return str(random.randint(4_000_000_000, 4_999_999_999))

def build_payload(req_id, sap_type, sap_reason, invoice_no, claim_id, shipping):
    """Realistic SAP return order request JSON."""
    return json.dumps({
        "PurchaseOrderNo": req_id,
        "OrderType": sap_type,
        "OrderReason": sap_reason,
        "ClaimId": claim_id,
        "ShipTo": rand_str("SH"),
        "SoldTo": rand_str("ST"),
        "ShippingMethod": shipping,
        "Items": [
            {
                "ReferenceDocument": str(invoice_no),
                "ReferenceItem": str(random.randint(10, 99)),
                "Material": rand_str("MAT"),
                "Quantity": str(random.randint(1, 50)),
                "Unit": "EA",
            }
            for _ in range(random.randint(1, 4))
        ],
    }, separators=(",", ":"))

# ── Schemas ───────────────────────────────────────────────────────────────────
RETURN_ORDER_SCHEMA = pa.schema([
    pa.field("return_order_request_id", pa.string()),
    pa.field("return_order_no",          pa.string()),   # SAP VBELN, null if Failed
    pa.field("return_order_status",      pa.string()),
    pa.field("return_order_sap_type",    pa.string()),
    pa.field("return_order_sap_reason",  pa.string()),
    pa.field("return_order_type",        pa.string()),   # invoice / order
    pa.field("return_order_error_msg",   pa.string()),   # populated on Failed
    pa.field("claim_id",                 pa.string()),
    pa.field("shipping_method",          pa.string()),
    pa.field("payload",                  pa.string()),   # raw JSON sent to SAP
    pa.field("created_at",               pa.string()),
])


# ── Generation ────────────────────────────────────────────────────────────────

def generate_month(year: int, month: int):
    """Generate return_order rows for one month."""
    orders = defaultdict(list)
    base_date = datetime(year, month, 1)

    for i in range(ORDERS_PER_MONTH):
        claim_id  = rand_str(random.choice(CLAIM_PREFIXES), 10)
        req_id    = f"{claim_id}-{i:04d}"
        sap_type  = random.choice(SAP_TYPES)
        sap_reason= random.choice(SAP_REASONS)
        ret_type  = random.choice(RETURN_TYPES)
        shipping  = random.choice(SHIPPING_METHODS)
        status    = random.choices(STATUSES, STATUS_WEIGHTS)[0]
        inv_no    = rand_invoice_number()
        created   = (base_date + timedelta(days=random.randint(0, 27),
                                           hours=random.randint(0, 23),
                                           minutes=random.randint(0, 59))
                     ).strftime("%Y-%m-%d %H:%M:%S")

        payload   = build_payload(req_id, sap_type, sap_reason, inv_no, claim_id, shipping)
        order_no  = rand_sap_order_number() if status != "Failed" else None
        error_msg = random.choice(ERROR_MESSAGES) if status == "Failed" else None

        orders["return_order_request_id"].append(req_id)
        orders["return_order_no"].append(order_no)
        orders["return_order_status"].append(status)
        orders["return_order_sap_type"].append(sap_type)
        orders["return_order_sap_reason"].append(sap_reason)
        orders["return_order_type"].append(ret_type)
        orders["return_order_error_msg"].append(error_msg)
        orders["claim_id"].append(claim_id)
        orders["shipping_method"].append(shipping)
        orders["payload"].append(payload)
        orders["created_at"].append(created)

    return pa.table(orders, schema=RETURN_ORDER_SCHEMA)


def upload_parquet(s3, table: pa.Table, key: str):
    buf = io.BytesIO()
    pq.write_table(table, buf, compression="snappy")
    data = buf.getvalue()
    s3.put_object(Bucket=BUCKET, Key=key, Body=data)
    print(f"  uploaded s3://{BUCKET}/{key}  ({len(data)/1024:.0f} KB, {len(table):,} rows)")


def main():
    s3 = boto3.client("s3", region_name=REGION)

    for year in range(START_YEAR, END_YEAR + 1):
        for month in range(1, 13):
            print(f"\n{year}/{month:02d}")
            orders_tbl = generate_month(year, month)
            upload_parquet(s3, orders_tbl,
                f"{PREFIX}/return_orders/year={year}/month={month:02d}/data.parquet")

    print("\nDone.")


if __name__ == "__main__":
    main()
