/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.networkstack.tethering;

import static android.system.OsConstants.ETH_P_IPV6;

import static com.android.networkstack.tethering.util.TetheringUtils.getTetheringJniLibraryName;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.net.MacAddress;
import android.os.Build;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;

import com.android.net.module.util.BpfMap;
import com.android.net.module.util.SingleWriterBpfMap;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.R)
public final class BpfMapTest {
    // Sync from packages/modules/Connectivity/bpf_progs/offload.c.
    private static final int TEST_MAP_SIZE = 16;
    private static final String TETHER_DOWNSTREAM6_FS_PATH =
            "/sys/fs/bpf/tethering/map_test_tether_downstream6_map";

    private ArrayMap<TetherDownstream6Key, Tether6Value> mTestData;

    private BpfMap<TetherDownstream6Key, Tether6Value> mTestMap;

    private final boolean mShouldTestSingleWriterMap;

    @Parameterized.Parameters
    public static Collection<Boolean> shouldTestSingleWriterMap() {
        return Arrays.asList(true, false);
    }

    public BpfMapTest(boolean shouldTestSingleWriterMap) {
        mShouldTestSingleWriterMap = shouldTestSingleWriterMap;
    }

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary(getTetheringJniLibraryName());
    }

    @Before
    public void setUp() throws Exception {
        mTestData = new ArrayMap<>();
        mTestData.put(createTetherDownstream6Key(101, "00:00:00:00:00:aa", "2001:db8::1"),
                createTether6Value(11, "00:00:00:00:00:0a", "11:11:11:00:00:0b",
                ETH_P_IPV6, 1280));
        mTestData.put(createTetherDownstream6Key(102, "00:00:00:00:00:bb", "2001:db8::2"),
                createTether6Value(22, "00:00:00:00:00:0c", "22:22:22:00:00:0d",
                ETH_P_IPV6, 1400));
        mTestData.put(createTetherDownstream6Key(103, "00:00:00:00:00:cc", "2001:db8::3"),
                createTether6Value(33, "00:00:00:00:00:0e", "33:33:33:00:00:0f",
                ETH_P_IPV6, 1500));

        initTestMap();
    }

    private BpfMap<TetherDownstream6Key, Tether6Value> openTestMap() throws Exception {
        return mShouldTestSingleWriterMap
                ? new SingleWriterBpfMap<>(TETHER_DOWNSTREAM6_FS_PATH, TetherDownstream6Key.class,
                Tether6Value.class)
                : new BpfMap<>(TETHER_DOWNSTREAM6_FS_PATH, TetherDownstream6Key.class,
                        Tether6Value.class);
    }

    private void initTestMap() throws Exception {
        mTestMap = openTestMap();
        mTestMap.forEach((key, value) -> {
            try {
                assertTrue(mTestMap.deleteEntry(key));
            } catch (ErrnoException e) {
                fail("Fail to delete the key " + key + ": " + e);
            }
        });
        assertNull(mTestMap.getFirstKey());
        assertTrue(mTestMap.isEmpty());
    }

    private TetherDownstream6Key createTetherDownstream6Key(int iif, String mac,
            String address) throws Exception {
        final MacAddress dstMac = MacAddress.fromString(mac);
        final InetAddress ipv6Address = InetAddress.getByName(address);

        return new TetherDownstream6Key(iif, dstMac, ipv6Address.getAddress());
    }

    private Tether6Value createTether6Value(int oif, String src, String dst, int proto, int pmtu) {
        final MacAddress srcMac = MacAddress.fromString(src);
        final MacAddress dstMac = MacAddress.fromString(dst);

        return new Tether6Value(oif, dstMac, srcMac, proto, pmtu);
    }

    @Test
    public void testGetFd() throws Exception {
        try (BpfMap readOnlyMap = new BpfMap<>(TETHER_DOWNSTREAM6_FS_PATH, BpfMap.BPF_F_RDONLY,
                TetherDownstream6Key.class, Tether6Value.class)) {
            assertNotNull(readOnlyMap);
            try {
                readOnlyMap.insertEntry(mTestData.keyAt(0), mTestData.valueAt(0));
                fail("Writing RO map should throw ErrnoException");
            } catch (ErrnoException expected) {
                assertEquals(OsConstants.EPERM, expected.errno);
            }
        }
        try (BpfMap writeOnlyMap = new BpfMap<>(TETHER_DOWNSTREAM6_FS_PATH, BpfMap.BPF_F_WRONLY,
                TetherDownstream6Key.class, Tether6Value.class)) {
            assertNotNull(writeOnlyMap);
            try {
                writeOnlyMap.getFirstKey();
                fail("Reading WO map should throw ErrnoException");
            } catch (ErrnoException expected) {
                assertEquals(OsConstants.EPERM, expected.errno);
            }
        }
        try (BpfMap readWriteMap = new BpfMap<>(TETHER_DOWNSTREAM6_FS_PATH,
                TetherDownstream6Key.class, Tether6Value.class)) {
            assertNotNull(readWriteMap);
        }
    }

    @Test
    public void testIsEmpty() throws Exception {
        assertNull(mTestMap.getFirstKey());
        assertTrue(mTestMap.isEmpty());

        mTestMap.insertEntry(mTestData.keyAt(0), mTestData.valueAt(0));
        assertFalse(mTestMap.isEmpty());

        mTestMap.deleteEntry((mTestData.keyAt(0)));
        assertTrue(mTestMap.isEmpty());
    }

    @Test
    public void testGetFirstKey() throws Exception {
        // getFirstKey on an empty map returns null.
        assertFalse(mTestMap.containsKey(mTestData.keyAt(0)));
        assertNull(mTestMap.getFirstKey());
        assertNull(mTestMap.getValue(mTestData.keyAt(0)));

        // getFirstKey on a non-empty map returns the first key.
        mTestMap.insertEntry(mTestData.keyAt(0), mTestData.valueAt(0));
        assertEquals(mTestData.keyAt(0), mTestMap.getFirstKey());
    }

    @Test
    public void testGetNextKey() throws Exception {
        // [1] If the passed-in key is not found on empty map, return null.
        final TetherDownstream6Key nonexistentKey =
                createTetherDownstream6Key(1234, "00:00:00:00:00:01", "2001:db8::10");
        assertNull(mTestMap.getNextKey(nonexistentKey));

        // [2] If the passed-in key is null on empty map, throw NullPointerException.
        try {
            mTestMap.getNextKey(null);
            fail("Getting next key with null key should throw NullPointerException");
        } catch (NullPointerException expected) { }

        // The BPF map has one entry now.
        final ArrayMap<TetherDownstream6Key, Tether6Value> resultMap =
                new ArrayMap<>();
        mTestMap.insertEntry(mTestData.keyAt(0), mTestData.valueAt(0));
        resultMap.put(mTestData.keyAt(0), mTestData.valueAt(0));

        // [3] If the passed-in key is the last key, return null.
        // Because there is only one entry in the map, the first key equals the last key.
        final TetherDownstream6Key lastKey = mTestMap.getFirstKey();
        assertNull(mTestMap.getNextKey(lastKey));

        // The BPF map has two entries now.
        mTestMap.insertEntry(mTestData.keyAt(1), mTestData.valueAt(1));
        resultMap.put(mTestData.keyAt(1), mTestData.valueAt(1));

        // [4] If the passed-in key is found, return the next key.
        TetherDownstream6Key nextKey = mTestMap.getFirstKey();
        while (nextKey != null) {
            if (resultMap.remove(nextKey).equals(nextKey)) {
                fail("Unexpected result: " + nextKey);
            }
            nextKey = mTestMap.getNextKey(nextKey);
        }
        assertTrue(resultMap.isEmpty());

        // [5] If the passed-in key is not found on non-empty map, return the first key.
        assertEquals(mTestMap.getFirstKey(), mTestMap.getNextKey(nonexistentKey));

        // [6] If the passed-in key is null on non-empty map, throw NullPointerException.
        try {
            mTestMap.getNextKey(null);
            fail("Getting next key with null key should throw NullPointerException");
        } catch (NullPointerException expected) { }
    }

    @Test
    public void testUpdateEntry() throws Exception {
        final TetherDownstream6Key key = mTestData.keyAt(0);
        final Tether6Value value = mTestData.valueAt(0);
        final Tether6Value value2 = mTestData.valueAt(1);
        assertFalse(mTestMap.deleteEntry(key));

        // updateEntry will create an entry if it does not exist already.
        mTestMap.updateEntry(key, value);
        assertTrue(mTestMap.containsKey(key));
        final Tether6Value result = mTestMap.getValue(key);
        assertEquals(value, result);

        // updateEntry will update an entry that already exists.
        mTestMap.updateEntry(key, value2);
        assertTrue(mTestMap.containsKey(key));
        final Tether6Value result2 = mTestMap.getValue(key);
        assertEquals(value2, result2);

        assertTrue(mTestMap.deleteEntry(key));
        assertFalse(mTestMap.containsKey(key));
    }

    @Test
    public void testInsertOrReplaceEntry() throws Exception {
        final TetherDownstream6Key key = mTestData.keyAt(0);
        final Tether6Value value = mTestData.valueAt(0);
        final Tether6Value value2 = mTestData.valueAt(1);
        assertFalse(mTestMap.deleteEntry(key));

        // insertOrReplaceEntry will create an entry if it does not exist already.
        assertTrue(mTestMap.insertOrReplaceEntry(key, value));
        assertTrue(mTestMap.containsKey(key));
        final Tether6Value result = mTestMap.getValue(key);
        assertEquals(value, result);

        // updateEntry will update an entry that already exists.
        assertFalse(mTestMap.insertOrReplaceEntry(key, value2));
        assertTrue(mTestMap.containsKey(key));
        final Tether6Value result2 = mTestMap.getValue(key);
        assertEquals(value2, result2);

        assertTrue(mTestMap.deleteEntry(key));
        assertFalse(mTestMap.containsKey(key));
    }

    @Test
    public void testInsertReplaceEntry() throws Exception {
        final TetherDownstream6Key key = mTestData.keyAt(0);
        final Tether6Value value = mTestData.valueAt(0);
        final Tether6Value value2 = mTestData.valueAt(1);

        try {
            mTestMap.replaceEntry(key, value);
            fail("Replacing non-existent key " + key + " should throw NoSuchElementException");
        } catch (NoSuchElementException expected) { }
        assertFalse(mTestMap.containsKey(key));

        mTestMap.insertEntry(key, value);
        assertTrue(mTestMap.containsKey(key));
        final Tether6Value result = mTestMap.getValue(key);
        assertEquals(value, result);
        try {
            mTestMap.insertEntry(key, value);
            fail("Inserting existing key " + key + " should throw IllegalStateException");
        } catch (IllegalStateException expected) { }

        mTestMap.replaceEntry(key, value2);
        assertTrue(mTestMap.containsKey(key));
        final Tether6Value result2 = mTestMap.getValue(key);
        assertEquals(value2, result2);
    }

    @Test
    public void testIterateBpfMap() throws Exception {
        final ArrayMap<TetherDownstream6Key, Tether6Value> resultMap =
                new ArrayMap<>(mTestData);

        for (int i = 0; i < resultMap.size(); i++) {
            mTestMap.insertEntry(resultMap.keyAt(i), resultMap.valueAt(i));
        }

        mTestMap.forEach((key, value) -> {
            if (!value.equals(resultMap.remove(key))) {
                fail("Unexpected result: " + key + ", value: " + value);
            }
        });
        assertTrue(resultMap.isEmpty());
    }

    @Test
    public void testIterateEmptyMap() throws Exception {
        // Can't use an int because variables used in a lambda must be final.
        final AtomicInteger count = new AtomicInteger();
        mTestMap.forEach((key, value) -> count.incrementAndGet());
        // Expect that the consumer was never called.
        assertEquals(0, count.get());
    }

    @Test
    public void testIterateDeletion() throws Exception {
        final ArrayMap<TetherDownstream6Key, Tether6Value> resultMap =
                new ArrayMap<>(mTestData);

        for (int i = 0; i < resultMap.size(); i++) {
            mTestMap.insertEntry(resultMap.keyAt(i), resultMap.valueAt(i));
        }

        // Can't use an int because variables used in a lambda must be final.
        final AtomicInteger count = new AtomicInteger();
        mTestMap.forEach((key, value) -> {
            try {
                assertTrue(mTestMap.deleteEntry(key));
            } catch (ErrnoException e) {
                fail("Fail to delete key " + key + ": " + e);
            }
            if (!value.equals(resultMap.remove(key))) {
                fail("Unexpected result: " + key + ", value: " + value);
            }
            count.incrementAndGet();
        });
        assertEquals(3, count.get());
        assertTrue(resultMap.isEmpty());
        assertNull(mTestMap.getFirstKey());
    }

    @Test
    public void testClear() throws Exception {
        // Clear an empty map.
        assertTrue(mTestMap.isEmpty());
        mTestMap.clear();

        // Clear a map with some data in it.
        final ArrayMap<TetherDownstream6Key, Tether6Value> resultMap =
                new ArrayMap<>(mTestData);
        for (int i = 0; i < resultMap.size(); i++) {
            mTestMap.insertEntry(resultMap.keyAt(i), resultMap.valueAt(i));
        }
        assertFalse(mTestMap.isEmpty());
        mTestMap.clear();
        assertTrue(mTestMap.isEmpty());
    }

    @Test
    public void testMapContentsCorrectOnOpen() throws Exception {
        final BpfMap<TetherDownstream6Key, Tether6Value> map1, map2;

        map1 = openTestMap();
        map1.clear();
        for (int i = 0; i < mTestData.size(); i++) {
            map1.insertEntry(mTestData.keyAt(i), mTestData.valueAt(i));
        }

        // We can't close and reopen map1, because close does nothing. Open another map instead.
        map2 = openTestMap();
        for (int i = 0; i < mTestData.size(); i++) {
            assertEquals(mTestData.valueAt(i), map2.getValue(mTestData.keyAt(i)));
        }

        map1.clear();
    }

    @Test
    public void testInsertOverflow() throws Exception {
        final ArrayMap<TetherDownstream6Key, Tether6Value> testData =
                new ArrayMap<>();

        // Build test data for TEST_MAP_SIZE + 1 entries.
        for (int i = 1; i <= TEST_MAP_SIZE + 1; i++) {
            testData.put(
                    createTetherDownstream6Key(i, "00:00:00:00:00:01", "2001:db8::1"),
                    createTether6Value(100, "de:ad:be:ef:00:01", "de:ad:be:ef:00:02",
                    ETH_P_IPV6, 1500));
        }

        // Insert #TEST_MAP_SIZE test entries to the map. The map has reached the limit.
        for (int i = 0; i < TEST_MAP_SIZE; i++) {
            mTestMap.insertEntry(testData.keyAt(i), testData.valueAt(i));
        }

        // The map won't allow inserting any more entries.
        try {
            mTestMap.insertEntry(testData.keyAt(TEST_MAP_SIZE), testData.valueAt(TEST_MAP_SIZE));
            fail("Writing too many entries should throw ErrnoException");
        } catch (ErrnoException expected) {
            // Expect that can't insert the entry anymore because the number of elements in the
            // map reached the limit. See man-pages/bpf.
            assertEquals(OsConstants.E2BIG, expected.errno);
        }
    }

    @Test
    public void testOpenNonexistentMap() throws Exception {
        try {
            final BpfMap<TetherDownstream6Key, Tether6Value> nonexistentMap = new BpfMap<>(
                    "/sys/fs/bpf/tethering/nonexistent",
                    TetherDownstream6Key.class, Tether6Value.class);
        } catch (ErrnoException expected) {
            assertEquals(OsConstants.ENOENT, expected.errno);
        }
    }

    private static int getNumOpenBpfMapFds() throws Exception {
        int numFds = 0;
        File[] openFiles = new File("/proc/self/fd").listFiles();
        for (int i = 0; i < openFiles.length; i++) {
            final Path path = openFiles[i].toPath();
            if (!Files.isSymbolicLink(path)) continue;
            if ("anon_inode:bpf-map".equals(Files.readSymbolicLink(path).toString())) {
                numFds++;
            }
        }
        assertNotEquals("Couldn't find any BPF map fds opened by this process", 0, numFds);
        return numFds;
    }

    @Test
    public void testNoFdLeaks() throws Exception {
        // Due to #setUp has called #initTestMap to open map and BpfMap is using persistent fd
        // cache, expect that the fd amount is not increased in the iterations.
        // See the comment of BpfMap#close.
        final int iterations = 1000;
        final int before = getNumOpenBpfMapFds();
        for (int i = 0; i < iterations; i++) {
            try (BpfMap<TetherDownstream6Key, Tether6Value> map = new BpfMap<>(
                    TETHER_DOWNSTREAM6_FS_PATH,
                    TetherDownstream6Key.class, Tether6Value.class)) {
                // do nothing
            }
        }
        final int after = getNumOpenBpfMapFds();

        // Check that the number of open fds is the same as before.
        assertEquals("Fd leak after " + iterations + " iterations: ", before, after);
    }

    @Test
    public void testNullKey() {
        assertThrows(NullPointerException.class, () ->
                mTestMap.insertOrReplaceEntry(null, mTestData.valueAt(0)));
    }

    private void runBenchmarkThread(BpfMap<TetherDownstream6Key, Tether6Value> map,
            CompletableFuture<Integer> future, int runtimeMs) {
        int numReads = 0;
        final Random r = new Random();
        final long start = SystemClock.elapsedRealtime();
        final long stop = start + runtimeMs;
        while (SystemClock.elapsedRealtime() < stop) {
            try {
                final Tether6Value v = map.getValue(mTestData.keyAt(r.nextInt(mTestData.size())));
                assertNotNull(v);
                numReads++;
            } catch (Exception e) {
                future.completeExceptionally(e);
                return;
            }
        }
        future.complete(numReads);
    }

    @Test
    public void testSingleWriterCacheEffectiveness() throws Exception {
        assumeTrue(mShouldTestSingleWriterMap);

        // Ensure the map is not empty.
        for (int i = 0; i < mTestData.size(); i++) {
            mTestMap.insertEntry(mTestData.keyAt(i), mTestData.valueAt(i));
        }

        // Benchmark parameters.
        final int timeoutMs = 5_000;  // Only hit if threads don't complete.
        final int benchmarkTimeMs = 2_000;
        final int minReads = 50;
        // Local testing on cuttlefish suggests that caching is ~10x faster.
        // Only require 3x to reduce test flakiness.
        final int expectedSpeedup = 3;

        final BpfMap cachedMap = new SingleWriterBpfMap(TETHER_DOWNSTREAM6_FS_PATH,
                TetherDownstream6Key.class, Tether6Value.class);
        final BpfMap uncachedMap = new BpfMap(TETHER_DOWNSTREAM6_FS_PATH,
                TetherDownstream6Key.class, Tether6Value.class);

        final CompletableFuture<Integer> cachedResult = new CompletableFuture<>();
        final CompletableFuture<Integer> uncachedResult = new CompletableFuture<>();

        new Thread(() -> runBenchmarkThread(uncachedMap, uncachedResult, benchmarkTimeMs)).start();
        new Thread(() -> runBenchmarkThread(cachedMap, cachedResult, benchmarkTimeMs)).start();

        final int cached = cachedResult.get(timeoutMs, TimeUnit.MILLISECONDS);
        final int uncached = uncachedResult.get(timeoutMs, TimeUnit.MILLISECONDS);

        // Uncomment to see benchmark results.
        // fail("Cached " + cached + ", uncached " + uncached + ": " + cached / uncached  +"x");

        assertTrue("Less than " + minReads + "cached reads observed", cached > minReads);
        assertTrue("Less than " + minReads + "uncached reads observed", uncached > minReads);
        assertTrue("Cached map not at least " + expectedSpeedup + "x faster",
                cached > expectedSpeedup * uncached);
    }
}
