package com.fightforfuture.cmp.service;

import com.fightforfuture.cmp.config.AthenaProperties;
import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AthenaService {

    private final AthenaClient       athenaClient;
    private final AthenaProperties   props;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Delta load — fetch invoices where invoice_number > fromInvoiceNumber.
     * Used for ongoing hourly sync.
     */
    public List<AthenaInvoiceRow> fetchDelta(long fromInvoiceNumber) {
        log.info("[Athena] Delta query: invoice_number > {}", fromInvoiceNumber);
        String sql = String.format(
                "SELECT * FROM %s.v_invoice_delta WHERE invoice_number > %d",
                props.getDatabase(), fromInvoiceNumber);
        return executeAndMap(sql);
    }

    /**
     * Initial load — fetch all invoices for a specific year/month partition.
     * Called month by month to avoid loading millions of rows in one shot.
     */
    public List<AthenaInvoiceRow> fetchByMonth(int year, int month) {
        log.info("[Athena] Initial load: year={} month={}", year, String.format("%02d", month));
        String sql = String.format(
                "SELECT * FROM %s.v_all_invoices WHERE year = '%d' AND month = '%02d'",
                props.getDatabase(), year, month);
        return executeAndMap(sql);
    }

    /**
     * Initial load — fetch ALL invoices for an entire year.
     * WARNING: ~1M rows per year. Demonstrates heap/WAL pressure.
     * Use fetchByMonth() in production.
     */
    public List<AthenaInvoiceRow> fetchByYear(int year) {
        log.warn("[Athena] Fetching FULL YEAR {} — expect high memory usage!", year);
        String sql = String.format(
                "SELECT * FROM %s.v_all_invoices WHERE year = '%d'",
                props.getDatabase(), year);
        return executeAndMap(sql);
    }

    /**
     * Initial load — fetch invoices for a 6-month window.
     * Partition-friendly: uses year + month range → Athena only scans 6 folders.
     * Good balance between number of API calls and memory usage (~500K rows per call).
     *
     * @param year      the year
     * @param fromMonth start month (1-12)
     * @param toMonth   end month   (1-12)
     */
    public List<AthenaInvoiceRow> fetchByMonthRange(int year, int fromMonth, int toMonth) {
        log.info("[Athena] Fetching {}/{} - {}/{}", year, fromMonth, year, toMonth);
        String sql = String.format(
                "SELECT * FROM %s.v_all_invoices WHERE year = '%d' AND CAST(month AS INT) BETWEEN %d AND %d",
                props.getDatabase(), year, fromMonth, toMonth);
        return executeAndMap(sql);
    }

    // ── Core: execute → poll → page results ──────────────────────────────────

    private List<AthenaInvoiceRow> executeAndMap(String sql) {
        String queryExecutionId = startQuery(sql);
        waitForCompletion(queryExecutionId);
        List<Map<String, String>> rawRows = fetchAllPages(queryExecutionId);
        log.info("[Athena] Fetched {} raw rows", rawRows.size());
        return rawRows.stream().map(this::mapRow).collect(Collectors.toList());
    }

    /**
     * Submit the query to Athena and return the execution ID.
     */
    private String startQuery(String sql) {
        StartQueryExecutionResponse resp = athenaClient.startQueryExecution(r -> r
                .queryString(sql)
                .workGroup(props.getWorkgroup())
                .queryExecutionContext(c -> c.database(props.getDatabase())));

        String id = resp.queryExecutionId();
        log.info("[Athena] Query started: {}", id);
        return id;
    }

    /**
     * Poll every pollIntervalMs until the query is SUCCEEDED or FAILED.
     */
    private void waitForCompletion(String queryExecutionId) {
        for (int attempt = 0; attempt < props.getMaxPollAttempts(); attempt++) {
            try {
                Thread.sleep(props.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling interrupted", e);
            }

            GetQueryExecutionResponse resp = athenaClient.getQueryExecution(r ->
                    r.queryExecutionId(queryExecutionId));

            QueryExecutionStatus status = resp.queryExecution().status();
            QueryExecutionState state   = status.state();

            log.debug("[Athena] Poll attempt {}/{} — state: {}", attempt + 1, props.getMaxPollAttempts(), state);

            switch (state) {
                case SUCCEEDED -> { return; }
                case FAILED, CANCELLED -> throw new RuntimeException(
                        "[Athena] Query %s — reason: %s".formatted(
                                state, status.stateChangeReason()));
                default -> { /* QUEUED or RUNNING — keep polling */ }
            }
        }

        throw new RuntimeException("[Athena] Query timed out after %d attempts".formatted(props.getMaxPollAttempts()));
    }

    /**
     * Page through all result pages and return rows as List<Map<column, value>>.
     * Athena returns max 1000 rows per page. First page includes the header row.
     */
    private List<Map<String, String>> fetchAllPages(String queryExecutionId) {
        List<Map<String, String>> allRows  = new ArrayList<>();
        List<String>              headers  = new ArrayList<>();
        String                    nextToken = null;
        boolean                   firstPage = true;

        do {
            String finalNextToken = nextToken;
            GetQueryResultsResponse page = athenaClient.getQueryResults(r -> {
                r.queryExecutionId(queryExecutionId);
                if (finalNextToken != null) r.nextToken(finalNextToken);
            });

            List<Row> rows = page.resultSet().rows();

            if (firstPage) {
                // First row of first page is always the column header
                headers = rows.get(0).data().stream()
                        .map(Datum::varCharValue)
                        .collect(Collectors.toList());
                rows = rows.subList(1, rows.size());
                firstPage = false;
            }

            for (Row row : rows) {
                List<String> values = row.data().stream()
                        .map(Datum::varCharValue)
                        .collect(Collectors.toList());

                Map<String, String> mapped = new java.util.LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    mapped.put(headers.get(i), i < values.size() ? values.get(i) : null);
                }
                allRows.add(mapped);
            }

            nextToken = page.nextToken();

        } while (nextToken != null);

        return allRows;
    }

    // ── Row mapper: Map<String,String> → AthenaInvoiceRow ────────────────────

    private AthenaInvoiceRow mapRow(Map<String, String> r) {
        return AthenaInvoiceRow.builder()
                // header
                .invoiceNumber(parseLong(r.get("invoice_number")))
                .customerCode(r.get("customer_code"))
                .currency(r.get("currency"))
                .invoiceDate(parseDate(r.get("invoice_date")))
                .invoiceAmount(parseBigDecimal(r.get("invoice_amount")))
                .billingType(r.get("billing_type"))
                .channel(r.get("channel"))
                .salesOrg(r.get("sales_org"))
                .countryCode(r.get("country_code"))
                // line item
                .partNumber(r.get("part_number"))
                .lineItemNo(r.get("line_item_no"))
                .boschMaterial(r.get("bosch_material"))
                .customerMaterial(r.get("customer_material"))
                .description(r.get("description"))
                .itemCategory(r.get("item_category"))
                .invoiceQuantity(parseBigDecimal(r.get("invoice_quantity")))
                .submittedQuantity(parseBigDecimal(r.get("submitted_quantity")))
                .netValue(parseBigDecimal(r.get("net_value")))
                .build();
    }

    // ── Type parsers ──────────────────────────────────────────────────────────

    private Long parseLong(String v) {
        return (v == null || v.isBlank()) ? null : Long.parseLong(v.trim());
    }

    private BigDecimal parseBigDecimal(String v) {
        return (v == null || v.isBlank()) ? null : new BigDecimal(v.trim());
    }

    private LocalDate parseDate(String v) {
        return (v == null || v.isBlank()) ? null : LocalDate.parse(v.trim());
    }
}
