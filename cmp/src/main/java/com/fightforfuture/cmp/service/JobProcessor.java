package com.fightforfuture.cmp.service;

import java.time.LocalDate;

public interface JobProcessor {

    /** Unique label stored in initial_job.job_type — e.g. "INVOICE". */
    String getJobType();

    /**
     * Fetch data for the given window from the data lake and persist it.
     *
     * @return number of records saved
     */
    int process(LocalDate startDate, LocalDate endDate);
}
