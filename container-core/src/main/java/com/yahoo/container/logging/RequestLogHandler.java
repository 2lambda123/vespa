// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.logging;

/**
 * @author Tony Vaagenes
 */
public interface RequestLogHandler {
    void log(RequestLogEntry entry);
}
