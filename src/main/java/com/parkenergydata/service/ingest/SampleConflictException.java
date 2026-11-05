package com.parkenergydata.service.ingest;
public class SampleConflictException extends IllegalArgumentException {
    public SampleConflictException() { super("SAMPLE_TIME_CONTENT_CONFLICT"); }
}
