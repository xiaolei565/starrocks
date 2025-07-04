// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common.path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.google.common.collect.Maps.newHashMap;

public class PathTrieTest {
    @Test
    public void testPath() {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("/a/b/c", "walla");
        trie.insert("a/d/g", "kuku");
        trie.insert("x/b/c", "lala");
        trie.insert("a/x/*", "one");
        trie.insert("a/b/*", "two");
        trie.insert("*/*/x", "three");
        trie.insert("{index}/insert/{docId}", "bingo");

        Assertions.assertEquals(trie.retrieve("a/b/c"), "walla");
        Assertions.assertEquals(trie.retrieve("a/d/g"), "kuku");
        Assertions.assertEquals(trie.retrieve("x/b/c"), "lala");
        Assertions.assertEquals(trie.retrieve("a/x/b"), "one");
        Assertions.assertEquals(trie.retrieve("a/b/d"), "two");

        Assertions.assertEquals(trie.retrieve("a/b"), null);
        Assertions.assertEquals(trie.retrieve("a/b/c/d"), null);
        Assertions.assertEquals(trie.retrieve("g/t/x"), "three");

        Map<String, String> params = newHashMap();
        Assertions.assertEquals(trie.retrieve("index1/insert/12", params), "bingo");
        Assertions.assertEquals(params.size(), 2);
        Assertions.assertEquals(params.get("index"), "index1");
        Assertions.assertEquals(params.get("docId"), "12");
    }

    @Test
    public void testEmptyPath() {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("/", "walla");
        Assertions.assertEquals(trie.retrieve(""), "walla");
    }

    @Test
    public void testDifferentNamesOnDifferentPath() {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("/a/{type}", "test1");
        trie.insert("/b/{name}", "test2");

        Map<String, String> params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/a/test", params), "test1");
        Assertions.assertEquals(params.get("type"), "test");

        params.clear();
        Assertions.assertEquals(trie.retrieve("/b/testX", params), "test2");
        Assertions.assertEquals(params.get("name"), "testX");
    }

    @Test
    public void testSameNameOnDifferentPath() {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("/a/c/{name}", "test1");
        trie.insert("/b/{name}", "test2");

        Map<String, String> params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/a/c/test", params), "test1");
        Assertions.assertEquals(params.get("name"), "test");

        params.clear();
        Assertions.assertEquals(trie.retrieve("/b/testX", params), "test2");
        Assertions.assertEquals(params.get("name"), "testX");
    }

    @Test
    public void testPreferNonWildcardExecution() {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("{test}", "test1");
        trie.insert("b", "test2");
        trie.insert("{test}/a", "test3");
        trie.insert("b/a", "test4");
        trie.insert("{test}/{testB}", "test5");
        trie.insert("{test}/x/{testC}", "test6");

        Map<String, String> params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/b", params), "test2");
        Assertions.assertEquals(trie.retrieve("/b/a", params), "test4");
        Assertions.assertEquals(trie.retrieve("/v/x", params), "test5");
        Assertions.assertEquals(trie.retrieve("/v/x/c", params), "test6");
    }

    @Test
    public void testSamePathConcreteResolution() {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("{x}/{y}/{z}", "test1");
        trie.insert("{x}/_y/{k}", "test2");

        Map<String, String> params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/a/b/c", params), "test1");
        Assertions.assertEquals(params.get("x"), "a");
        Assertions.assertEquals(params.get("y"), "b");
        Assertions.assertEquals(params.get("z"), "c");
        params.clear();
        Assertions.assertEquals(trie.retrieve("/a/_y/c", params), "test2");
        Assertions.assertEquals(params.get("x"), "a");
        Assertions.assertEquals(params.get("k"), "c");
    }

    @Test
    public void testNamedWildcardAndLookupWithWildcard() {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("x/{test}", "test1");
        trie.insert("{test}/a", "test2");
        trie.insert("/{test}", "test3");
        trie.insert("/{test}/_endpoint", "test4");
        trie.insert("/*/{test}/_endpoint", "test5");

        Map<String, String> params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/x/*", params), "test1");
        Assertions.assertEquals(params.get("test"), "*");

        params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/b/a", params), "test2");
        Assertions.assertEquals(params.get("test"), "b");

        params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/*", params), "test3");
        Assertions.assertEquals(params.get("test"), "*");

        params = newHashMap();
        Assertions.assertEquals(trie.retrieve("/*/_endpoint", params), "test4");
        Assertions.assertEquals(params.get("test"), "*");

        params = newHashMap();
        Assertions.assertEquals(trie.retrieve("a/*/_endpoint", params), "test5");
        Assertions.assertEquals(params.get("test"), "*");
    }
}
