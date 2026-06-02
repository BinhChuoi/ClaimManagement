FROM postgres:16-alpine

# Environment — override at runtime with --env or docker-compose
ENV POSTGRES_USER=postgres
ENV POSTGRES_PASSWORD=test123
ENV POSTGRES_DB=invoices

# Copy schema script into init directory.
# PostgreSQL automatically runs all .sql files here on first startup.
COPY sql/schema_minimal.sql /docker-entrypoint-initdb.d/01_schema.sql
