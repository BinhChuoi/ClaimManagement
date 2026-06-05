package com.fightforfuture.cmp.batch;

import com.fightforfuture.cmp.config.S3Properties;
import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.DelegatingSeekableInputStream;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class S3ParquetItemReader implements ItemReader<AthenaInvoiceRow>, ItemStream {

    private static final String CURSOR_KEY = "reader.cursor";

    private final S3Client     s3Client;
    private final S3Properties s3Properties;

    @Value("#{stepExecutionContext['startDate']}")
    private String startDate;

    private ParquetReader<GenericRecord> lineItemReader;
    private Map<Long, GenericRecord>     headerMap;
    private long                         cursor = 0;

    // ── ItemStream lifecycle ──────────────────────────────────────────────────

    @Override
    public void open(ExecutionContext ctx) {
        LocalDate date  = LocalDate.parse(startDate);
        int year  = date.getYear();
        int month = date.getMonthValue();

        log.info("[S3ParquetItemReader] Opening {}/{}", year, month);

        byte[] headerBytes   = downloadBytes(key("invoices_header",    year, month));
        byte[] lineItemBytes = downloadBytes(key("invoices_line_items", year, month));

        if (headerBytes.length == 0 || lineItemBytes.length == 0) {
            log.info("[S3ParquetItemReader] No data in S3 for {}/{} — will produce 0 rows", year, month);
            headerMap      = Map.of();
            lineItemReader = null;   // read() returns null immediately → 0 items
            return;
        }

        headerMap      = readAllIntoMap(headerBytes, "invoice_number");
        lineItemReader = openReader(lineItemBytes);

        cursor = ctx.getLong(CURSOR_KEY, 0L);
        if (cursor > 0) {
            log.info("[S3ParquetItemReader] Resuming from row {} — skipping {} already-committed rows",
                    cursor, cursor);
            fastForward(cursor);
        }

        log.info("[S3ParquetItemReader] Ready — {} headers, cursor={}", headerMap.size(), cursor);
    }

    @Override
    public AthenaInvoiceRow read() throws Exception {
        if (lineItemReader == null) return null;  // no data for this month
        GenericRecord li = lineItemReader.read();
        if (li == null) return null;
        cursor++;

        Long invoiceNumber = getLong(li, "invoice_number");
        GenericRecord h = headerMap.get(invoiceNumber);

        return AthenaInvoiceRow.builder()
                .invoiceNumber(invoiceNumber)
                .partNumber(longAsString(li, "part_number"))
                .lineItemNo(longAsString(li, "line_item_no"))
                .boschMaterial(longAsString(li, "bosch_material"))
                .customerMaterial(getString(li, "customer_material"))
                .description(getString(li, "description"))
                .itemCategory(getString(li, "item_category"))
                .invoiceQuantity(getDecimal(li, "invoice_quantity"))
                .submittedQuantity(getDecimal(li, "submitted_quantity"))
                .netValue(getDecimal(li, "net_value"))
                .customerCode(h != null ? longAsString(h, "customer_code") : null)
                .currency(h != null ? getString(h, "currency") : null)
                .invoiceDate(h != null ? getDate(h, "invoice_date") : null)
                .invoiceAmount(h != null ? getDecimal(h, "invoice_amount") : null)
                .billingType(h != null ? getString(h, "billing_type") : null)
                .channel(h != null ? getString(h, "channel") : null)
                .salesOrg(h != null ? getString(h, "sales_org") : null)
                .countryCode(h != null ? getString(h, "country_code") : null)
                .build();
    }

    @Override
    public void update(ExecutionContext ctx) {
        ctx.putLong(CURSOR_KEY, cursor);
    }

    @Override
    public void close() {
        try { if (lineItemReader != null) lineItemReader.close(); } catch (IOException ignored) {}
        headerMap = null;
        cursor = 0;
    }

    // ── S3 ────────────────────────────────────────────────────────────────────

    private String key(String table, int year, int month) {
        return String.format("%s/%s/year=%d/month=%02d/data.parquet",
                s3Properties.getProcessedPrefix(), table, year, month);
    }

    private static final byte[] EMPTY = new byte[0];

    /**
     * Downloads an S3 object into memory.
     * Returns empty byte array if the key doesn't exist (404) — e.g. months
     * not yet present in the data lake. The reader will produce 0 rows and the
     * job will complete cleanly with 0 records.
     */
    private byte[] downloadBytes(String s3Key) {
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(s3Properties.getDatalakeBucket())
                            .key(s3Key)
                            .build());
            byte[] bytes = response.asByteArray();
            log.debug("[S3ParquetItemReader] Downloaded {} ({} KB)", s3Key, bytes.length / 1024);
            return bytes;
        } catch (SdkServiceException e) {
            if (e.statusCode() == 404) {
                log.info("[S3ParquetItemReader] No data for {} — skipping (404)", s3Key);
                return EMPTY;
            }
            throw e;
        }
    }

    // ── Parquet (in-memory, no disk) ──────────────────────────────────────────

    private static final Configuration HADOOP_CONF;
    static {
        // Suppress "HADOOP_HOME unset" native-library warning on Windows
        System.setProperty("hadoop.home.dir", System.getProperty("java.io.tmpdir", "/tmp"));
        HADOOP_CONF = new Configuration();
        HADOOP_CONF.set("fs.defaultFS", "file:///");
    }

    private ParquetReader<GenericRecord> openReader(byte[] bytes) {
        try {
            return AvroParquetReader.<GenericRecord>builder(new InMemoryInputFile(bytes))
                    .withConf(HADOOP_CONF)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Parquet reader from bytes", e);
        }
    }

    private Map<Long, GenericRecord> readAllIntoMap(byte[] bytes, String keyField) {
        Map<Long, GenericRecord> map = new HashMap<>();
        try (ParquetReader<GenericRecord> reader = openReader(bytes)) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                Long key = getLong(record, keyField);
                if (key != null) map.put(key, record);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read Parquet bytes", e);
        }
        return map;
    }

    private void fastForward(long rows) {
        try {
            for (long i = 0; i < rows; i++) {
                if (lineItemReader.read() == null) break;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to fast-forward reader", e);
        }
    }

    // ── InMemoryInputFile — Parquet InputFile backed by a byte array ──────────

    private static final class InMemoryInputFile implements InputFile {

        private final byte[] data;

        InMemoryInputFile(byte[] data) {
            this.data = data;
        }

        @Override
        public long getLength() {
            return data.length;
        }

        @Override
        public SeekableInputStream newStream() {
            return new ByteArraySeekableStream(data);
        }
    }

    private static final class ByteArraySeekableStream extends DelegatingSeekableInputStream {

        private final ByteArrayInputStream inner;
        private final int                  totalLength;

        ByteArraySeekableStream(byte[] data) {
            super(new ByteArrayInputStream(data));
            this.inner       = (ByteArrayInputStream) getStream();
            this.totalLength = data.length;
            this.inner.mark(data.length); // mark at position 0 for reset
        }

        @Override
        public long getPos() {
            // current position = total bytes - bytes still available to read
            return totalLength - inner.available();
        }

        @Override
        public void seek(long newPos) {
            inner.reset();        // back to position 0
            inner.skip(newPos);   // forward to requested position
        }
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    private Long getLong(GenericRecord r, String field) {
        Object v = r.get(field);
        if (v == null) return null;
        if (v instanceof Long)    return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        String s = v.toString().trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }

    private String getString(GenericRecord r, String field) {
        Object v = r.get(field);
        return v == null ? null : v.toString();
    }

    private String longAsString(GenericRecord r, String field) {
        Long v = getLong(r, field);
        return v == null ? null : String.valueOf(v);
    }

    private BigDecimal getDecimal(GenericRecord r, String field) {
        Object v = r.get(field);
        if (v == null) return null;
        if (v instanceof Double)  return BigDecimal.valueOf((Double) v);
        if (v instanceof Float)   return BigDecimal.valueOf(((Float) v).doubleValue());
        if (v instanceof Long)    return BigDecimal.valueOf((Long) v);
        if (v instanceof Integer) return BigDecimal.valueOf((Integer) v);
        String s = v.toString().trim();
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    private LocalDate getDate(GenericRecord r, String field) {
        Object v = r.get(field);
        if (v == null) return null;
        if (v instanceof Integer) return LocalDate.ofEpochDay((Integer) v);
        String s = v.toString().trim();
        return s.isEmpty() ? null : LocalDate.parse(s);
    }
}
