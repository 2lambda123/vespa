// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.prelude.querytransform.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.prelude.query.CompositeItem;
import com.yahoo.prelude.query.Item;
import com.yahoo.prelude.query.NullItem;
import com.yahoo.prelude.query.WordItem;
import com.yahoo.prelude.querytransform.RecallSearcher;
import com.yahoo.search.searchchain.Execution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Simon Thoresen Hult
 */
public class RecallSearcherTestCase {

    @Test
    void testIgnoreEmptyProperty() {
        RecallSearcher searcher = new RecallSearcher();
        Query query = new Query();
        Result result = new Execution(searcher, Execution.Context.createContextStub()).search(query);
        assertNull(result.hits().getError());
        assertTrue(query.getModel().getQueryTree().getRoot() instanceof NullItem);
    }

    @Test
    void testDenyRankItems() {
        RecallSearcher searcher = new RecallSearcher();
        Query query = new Query("?recall=foo");
        Result result = new Execution(searcher, Execution.Context.createContextStub()).search(query);
        assertNotNull(result.hits().getError());
    }

    @Test
    void testParse() {
        List<String> empty = new ArrayList<>();
        assertQueryTree("?query=foo", List.of("foo"), empty);
        assertQueryTree("?recall=%2bfoo", empty, List.of("foo"));
        assertQueryTree("?query=foo&filter=bar&recall=%2bbaz", List.of("foo", "bar"), List.of("baz"));
        assertQueryTree("?query=foo+bar&filter=baz&recall=%2bcox", List.of("foo", "bar", "baz"), List.of("cox"));
        assertQueryTree("?query=foo&filter=bar+baz&recall=%2bcox", List.of("foo", "bar", "baz"), List.of("cox"));
        assertQueryTree("?query=foo&filter=bar&recall=-baz+%2bcox", List.of("foo", "bar"), List.of("baz", "cox"));
        assertQueryTree("?query=foo%20bar&recall=%2bbaz%20-cox", List.of("foo", "bar"), List.of("baz", "cox"));
    }

    private static void assertQueryTree(String query, List<String> ranked, List<String> unranked) {
        RecallSearcher searcher = new RecallSearcher();
        Query obj = new Query(query);
        Result result = new Execution(searcher, Execution.Context.createContextStub()).search(obj);
        if (result.hits().getError() != null) {
            fail(result.hits().getError().toString());
        }

        List<String> myRanked = new ArrayList<>(ranked);
        List<String> myUnranked = new ArrayList<>(unranked);

        Deque<Item> stack = new ArrayDeque<>();
        stack.push(obj.getModel().getQueryTree().getRoot());
        while (!stack.isEmpty()) {
            Item item = stack.pop();
            if (item instanceof WordItem) {
                String word = ((WordItem)item).getWord();
                if (item.isRanked()) {
                    int idx = myRanked.indexOf(word);
                    if (idx < 0) {
                        fail("Term '" + word + "' not expected as ranked term.");
                    }
                    myRanked.remove(idx);
                } else {
                    int idx = myUnranked.indexOf(word);
                    if (idx < 0) {
                        fail("Term '" + word + "' not expected as unranked term.");
                    }
                    myUnranked.remove(idx);
                }
            }
            if (item instanceof CompositeItem lst) {
                for (Iterator<?> it = lst.getItemIterator(); it.hasNext();) {
                    stack.push((Item)it.next());
                }
            }
        }

        if (!myRanked.isEmpty()) {
            fail("Ranked terms " + myRanked + " not found.");
        }
        if (!myUnranked.isEmpty()) {
            fail("Unranked terms " + myUnranked + " not found.");
        }
    }

}
