package com.eventticket.shared.storage;

/** What the store knows about an object without reading it. */
public record StoredObject(String contentType, long size) {
}
