// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.test.samples;

import ai.vespa.hosted.cd.InconclusiveTestException;
import ai.vespa.hosted.cd.ProductionTest;
import com.yahoo.vespa.testrunner.Expect;
import org.junit.jupiter.api.Test;

@ProductionTest
@Expect(inconclusive = 1, status = 3)
public class InconclusiveTest {

    @Test
    void test() { throw new InconclusiveTestException("soon"); }

}
