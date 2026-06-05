"""
Create Glue database + tables for return_orders and return_order_invoices,
then create Athena views.

Run after generate_return_orders.py has uploaded data to S3.
  python scripts/setup_glue_athena.py
"""

import boto3
import time

REGION          = "us-east-1"
BUCKET          = "levelupjourney-datalake-930388172688"
PREFIX          = "processed"
DATABASE        = "invoices_db"
ATHENA_OUTPUT   = f"s3://{BUCKET}/athena-results/"

glue    = boto3.client("glue",    region_name=REGION)
athena  = boto3.client("athena",  region_name=REGION)

# ── Helpers ───────────────────────────────────────────────────────────────────

def create_database():
    try:
        glue.create_database(DatabaseInput={"Name": DATABASE})
        print(f"Created database: {DATABASE}")
    except glue.exceptions.AlreadyExistsException:
        print(f"Database already exists: {DATABASE}")

def create_table(name: str, columns: list, location: str):
    try:
        glue.delete_table(DatabaseName=DATABASE, Name=name)
    except glue.exceptions.EntityNotFoundException:
        pass

    glue.create_table(
        DatabaseName=DATABASE,
        TableInput={
            "Name": name,
            "StorageDescriptor": {
                "Columns": columns,
                "Location": location,
                "InputFormat":  "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
                "OutputFormat": "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat",
                "SerdeInfo": {
                    "SerializationLibrary": "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe",
                    "Parameters": {"serialization.format": "1"},
                },
                "Compressed": True,
            },
            "PartitionKeys": [
                {"Name": "year",  "Type": "int"},
                {"Name": "month", "Type": "string"},
            ],
            "TableType": "EXTERNAL_TABLE",
            "Parameters": {
                "classification":          "parquet",
                "parquet.compression":     "SNAPPY",
                "projection.enabled":      "true",
                "projection.year.type":    "integer",
                "projection.year.range":   "2022,2030",
                "projection.month.type":   "integer",
                "projection.month.range":  "1,12",
                "projection.month.digits": "2",
                "storage.location.template": location + "/year=${year}/month=${month}",
            },
        },
    )
    print(f"Created table: {DATABASE}.{name}")

def run_athena(sql: str, desc: str):
    resp = athena.start_query_execution(
        QueryString=sql,
        QueryExecutionContext={"Database": DATABASE},
        ResultConfiguration={"OutputLocation": ATHENA_OUTPUT},
    )
    qid = resp["QueryExecutionId"]
    print(f"  [{desc}] query: {qid}")

    while True:
        status = athena.get_query_execution(QueryExecutionId=qid)
        state = status["QueryExecution"]["Status"]["State"]
        if state in ("SUCCEEDED", "FAILED", "CANCELLED"):
            if state != "SUCCEEDED":
                reason = status["QueryExecution"]["Status"].get("StateChangeReason", "")
                print(f"  FAILED: {reason}")
            else:
                print(f"  OK")
            break
        time.sleep(2)

# ── Table definitions ─────────────────────────────────────────────────────────

RETURN_ORDERS_COLUMNS = [
    {"Name": "return_order_request_id", "Type": "string"},
    {"Name": "return_order_no",          "Type": "string"},
    {"Name": "return_order_status",      "Type": "string"},
    {"Name": "return_order_sap_type",    "Type": "string"},
    {"Name": "return_order_sap_reason",  "Type": "string"},
    {"Name": "return_order_type",        "Type": "string"},
    {"Name": "return_order_error_msg",   "Type": "string"},
    {"Name": "claim_id",                 "Type": "string"},
    {"Name": "shipping_method",          "Type": "string"},
    {"Name": "payload",                  "Type": "string"},
    {"Name": "created_at",               "Type": "string"},
]

RETURN_ORDER_INVOICES_COLUMNS = [
    {"Name": "return_order_request_id", "Type": "string"},
    {"Name": "invoice_number",          "Type": "bigint"},
    {"Name": "invoice_type",            "Type": "string"},
    {"Name": "created_at",              "Type": "string"},
]

# ── Views ─────────────────────────────────────────────────────────────────────

VIEW_RETURN_ORDER_DETAIL = f"""
CREATE OR REPLACE VIEW v_return_order_detail AS
SELECT
    return_order_request_id,
    claim_id,
    return_order_no,
    return_order_status,
    return_order_sap_type,
    return_order_sap_reason,
    return_order_type,
    return_order_error_msg,
    shipping_method,
    created_at
FROM return_orders
"""

VIEW_RETURN_ORDER_SUMMARY = f"""
CREATE OR REPLACE VIEW v_return_order_summary AS
SELECT
    return_order_status,
    return_order_sap_type,
    return_order_type,
    COUNT(*)                       AS total_orders,
    COUNT(return_order_no)         AS sap_confirmed,
    COUNT(return_order_error_msg)  AS failed_count
FROM return_orders
GROUP BY 1, 2, 3
"""

# Filter by returnOrderRequestId — scan only the relevant partition via year/month
VIEW_BY_REQUEST_ID = f"""
CREATE OR REPLACE VIEW v_return_order_by_request_id AS
SELECT
    return_order_request_id,
    claim_id,
    return_order_no,
    return_order_status,
    return_order_sap_type,
    return_order_sap_reason,
    return_order_type,
    return_order_error_msg,
    shipping_method,
    payload,
    created_at,
    year,
    month
FROM return_orders
"""

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    print("1. Creating Glue database...")
    create_database()

    print("\n2. Creating Glue tables...")
    create_table(
        "return_orders",
        RETURN_ORDERS_COLUMNS,
        f"s3://{BUCKET}/{PREFIX}/return_orders",
    )

    print("\n3. Creating Athena views...")
    run_athena(VIEW_RETURN_ORDER_DETAIL,  "v_return_order_detail")
    run_athena(VIEW_RETURN_ORDER_SUMMARY, "v_return_order_summary")
    run_athena(VIEW_BY_REQUEST_ID,        "v_return_order_by_request_id")

    print("\nDone. Query example:")
    print(f"""
    SELECT * FROM {DATABASE}.v_return_order_by_request_id
    WHERE return_order_request_id = 'CLM1234567890-0001'
    """)


if __name__ == "__main__":
    main()
