package com.fightforfuture.cmp.util;

/**
 * Twitter Snowflake ID generator — produces globally unique 64-bit IDs.
 *
 * Layout (63 usable bits):
 *   [41 bits timestamp ms since EPOCH] [10 bits machineId] [12 bits sequence]
 *
 * Usage: long id = SnowflakeId.next();
 */
public final class SnowflakeId {

    // Custom epoch — 2024-01-01 00:00:00 UTC (reduces ID size early in deployment)
    private static final long EPOCH = 1_704_067_200_000L;

    private static final long MACHINE_ID_BITS  = 10L;
    private static final long SEQUENCE_BITS    = 12L;
    private static final long MAX_MACHINE_ID   = ~(-1L << MACHINE_ID_BITS);   // 1023
    private static final long MAX_SEQUENCE     = ~(-1L << SEQUENCE_BITS);     // 4095

    private static final long MACHINE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT  = MACHINE_ID_BITS + SEQUENCE_BITS;

    // Default machine ID — override via system property: -Dsnowflake.machine-id=2
    private static final long MACHINE_ID =
            Long.getLong("snowflake.machine-id", 1L) & MAX_MACHINE_ID;

    private static long sequence     = 0L;
    private static long lastTimestamp = -1L;

    private SnowflakeId() {}

    public static synchronized long next() {
        long now = System.currentTimeMillis();

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted in this ms — busy-wait for next ms
                while (now <= lastTimestamp) now = System.currentTimeMillis();
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = now;

        return ((now - EPOCH)   << TIMESTAMP_SHIFT)
             | (MACHINE_ID      << MACHINE_ID_SHIFT)
             | sequence;
    }
}
