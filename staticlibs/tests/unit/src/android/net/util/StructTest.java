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

package android.net.util;

import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.IpPrefix;
import android.net.util.Struct.Field;
import android.net.util.Struct.Type;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StructTest {

    // IPv6, 0 bytes of options, ifindex 15715755: 0x00efcdab, type 134 (RA), code 0, padding.
    private static final String HDR_EMPTY = "0a00" + "0000" + "abcdef00" + "8600000000000000";

    // BE16: 0xfeff, BE32: 0xfeffffff, BE64: 0xfffffffffffffffe, 0x7effffffffffffff
    private static final String NETWORK_ORDER_MSG = "feff" + "feffffff" + "fffffffffffffffe"
            + "7effffffffffffff";

    // S8: 0x7f, S16: 0x7fff, S32: 0x7fffffff, S64: 0x7fffffffffffffff
    private static final String SIGNED_DATA = "7f" + "ff7f" + "ffffff7f" + "ffffffffffffff7f";

    // nS8: 0x81, nS16: 0x8001, nS32: 0x80000001, nS64: 800000000000000001
    private static final String SIGNED_NEGATIVE_DATA = "81" + "0180" + "01000080"
            + "0100000000000080";

    // U8: 0xff, U16: 0xffff, U32: 0xffffffff, U64: 0xffffffffffffffff, U63: 0x7fffffffffffffff;
    private static final String UNSIGNED_DATA = "ff" + "ffff" + "ffffffff" + "ffffffffffffffff"
            + "ffffffffffffff7f";

    // PREF64 option, 2001:db8:3:4:5:6::/96, lifetime: 10064
    private static final String OPT_PREF64 = "2750" + "20010db80003000400050006";

    private <T> T doParsingMessageTest(final String hexString, Class<T> clazz) {
        final ByteBuffer buf = toByteBuffer(hexString);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return Struct.parse(clazz, buf);
    }

    static class HeaderMsgWithConstructor extends Struct {
        static int sType;
        static int sLength;

        @Field(order = 0, type = Type.U8, padding = 1)
        final short mFamily;
        @Field(order = 1, type = Type.U16)
        final int mLen;
        @Field(order = 2, type = Type.S32)
        final int mIfindex;
        @Field(order = 3, type = Type.U8)
        final short mIcmpType;
        @Field(order = 4, type = Type.U8, padding = 6)
        final short mIcmpCode;

        HeaderMsgWithConstructor(final short family, final int len, final int ifindex,
                final short type, final short code) {
            mFamily = family;
            mLen = len;
            mIfindex = ifindex;
            mIcmpType = type;
            mIcmpCode = code;
        }
    }

    @Test
    public void testClassWithExplicitConstructor() {
        final HeaderMsgWithConstructor msg = doParsingMessageTest(HDR_EMPTY,
                HeaderMsgWithConstructor.class);
        assertEquals(10, msg.mFamily);
        assertEquals(0, msg.mLen);
        assertEquals(15715755, msg.mIfindex);
        assertEquals(134, msg.mIcmpType);
        assertEquals(0, msg.mIcmpCode);
    }

    static class HeaderMsgWithoutConstructor extends Struct {
        static int sType;
        static int sLength;

        @Field(order = 0, type = Type.U8, padding = 1)
        short mFamily;
        @Field(order = 1, type = Type.U16)
        int mLen;
        @Field(order = 2, type = Type.S32)
        int mIfindex;
        @Field(order = 3, type = Type.U8)
        short mIcmpType;
        @Field(order = 4, type = Type.U8, padding = 6)
        short mIcmpCode;
    }

    @Test
    public void testClassWithDefaultConstrcutor() {
        final HeaderMsgWithoutConstructor msg = doParsingMessageTest(HDR_EMPTY,
                HeaderMsgWithoutConstructor.class);
        assertEquals(10, msg.mFamily);
        assertEquals(0, msg.mLen);
        assertEquals(15715755, msg.mIfindex);
        assertEquals(134, msg.mIcmpType);
        assertEquals(0, msg.mIcmpCode);
    }

    static class HeaderMessage {
        @Field(order = 0, type = Type.U8, padding = 1)
        short mFamily;
        @Field(order = 1, type = Type.U16)
        int mLen;
        @Field(order = 2, type = Type.S32)
        int mIfindex;
        @Field(order = 3, type = Type.U8)
        short mIcmpType;
        @Field(order = 4, type = Type.U8, padding = 6)
        short mIcmpCode;
    }

    @Test
    public void testInvalidClass_NotSubClass() {
        final ByteBuffer buf = toByteBuffer(HDR_EMPTY);
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(HeaderMessage.class, buf));
    }

    static class HeaderMessageMissingAnnotation extends Struct {
        @Field(order = 0, type = Type.U8, padding = 1)
        short mFamily;
        @Field(order = 1, type = Type.U16)
        int mLen;
        int mIfindex;
        @Field(order = 2, type = Type.U8)
        short mIcmpType;
        @Field(order = 3, type = Type.U8, padding = 6)
        short mIcmpCode;
    }

    @Test
    public void testInvalidClass_MissingAnnotationField() {
        final ByteBuffer buf = toByteBuffer(HDR_EMPTY);
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(HeaderMessageMissingAnnotation.class, buf));
    }

    static class NetworkOrderMessage extends Struct {
        @Field(order = 0, type = Type.BE16)
        final int mBE16;
        @Field(order = 1, type = Type.BE32)
        final long mBE32;
        @Field(order = 2, type = Type.BE64)
        final BigInteger mBE64;
        @Field(order = 3, type = Type.BE64)
        final long mBE63;

        NetworkOrderMessage(final int be16, final long be32, final BigInteger be64,
                final long be63) {
            mBE16 = be16;
            mBE32 = be32;
            mBE64 = be64;
            mBE63 = be63;
        }
    }

    @Test
    public void testNetworkOrder() {
        final NetworkOrderMessage msg = doParsingMessageTest(NETWORK_ORDER_MSG,
                NetworkOrderMessage.class);
        assertEquals(65279, msg.mBE16);
        assertEquals(4278190079L, msg.mBE32);
        assertEquals(new BigInteger("18374686479671623679"), msg.mBE64);
        assertEquals(9151314442816847871L, msg.mBE63);
    }

    static class UnsignedDataMessage extends Struct {
        @Field(order = 0, type = Type.U8)
        final short mU8;
        @Field(order = 1, type = Type.U16)
        final int mU16;
        @Field(order = 2, type = Type.U32)
        final long mU32;
        @Field(order = 3, type = Type.U64)
        final BigInteger mU64;
        @Field(order = 4, type = Type.U64)
        final long mU63;

        UnsignedDataMessage(final short u8, final int u16, final long u32, final BigInteger u64,
                final long u63) {
            mU8 = u8;
            mU16 = u16;
            mU32 = u32;
            mU64 = u64;
            mU63 = u63;
        }
    }

    @Test
    public void testUnsignedData() {
        final UnsignedDataMessage msg = doParsingMessageTest(UNSIGNED_DATA,
                UnsignedDataMessage.class);
        assertEquals(255, msg.mU8);
        assertEquals(65535, msg.mU16);
        assertEquals(4294967295L, msg.mU32);
        assertEquals(new BigInteger("18446744073709551615"), msg.mU64);
        assertEquals(9223372036854775807L, msg.mU63);
    }

    static class SignedDataMessage extends Struct {
        @Field(order = 0, type = Type.S8)
        final byte mS8;
        @Field(order = 1, type = Type.S16)
        final short mS16;
        @Field(order = 2, type = Type.S32)
        final int mS32;
        @Field(order = 3, type = Type.S64)
        final long mS64;
        @Field(order = 4, type = Type.S8)
        final byte mNS8;
        @Field(order = 5, type = Type.S16)
        final short mNS16;
        @Field(order = 6, type = Type.S32)
        final int mNS32;
        @Field(order = 7, type = Type.S64)
        final long mNS64;

        SignedDataMessage(final byte s8, final short s16, final int s32, final long s64,
                final byte nS8, final short nS16, final int nS32, final long nS64) {
            mS8 = s8;
            mS16 = s16;
            mS32 = s32;
            mS64 = s64;
            mNS8 = nS8;
            mNS16 = nS16;
            mNS32 = nS32;
            mNS64 = nS64;
        }
    }

    @Test
    public void testSignedData() {
        final SignedDataMessage msg = doParsingMessageTest(SIGNED_DATA + SIGNED_NEGATIVE_DATA,
                SignedDataMessage.class);
        assertEquals(127, msg.mS8);
        assertEquals(32767, msg.mS16);
        assertEquals(2147483647, msg.mS32);
        assertEquals(9223372036854775807L, msg.mS64);

        assertEquals(-127, msg.mNS8);
        assertEquals(-32767, msg.mNS16);
        assertEquals(-2147483647, msg.mNS32);
        assertEquals(-9223372036854775807L, msg.mNS64);
    }

    static class HeaderMessageWithDuplicateOrder extends Struct {
        @Field(order = 0, type = Type.U8, padding = 1)
        short mFamily;
        @Field(order = 1, type = Type.U16)
        int mLen;
        @Field(order = 2, type = Type.S32)
        int mIfindex;
        @Field(order = 2, type = Type.U8)
        short mIcmpType;
        @Field(order = 3, type = Type.U8, padding = 6)
        short mIcmpCode;
    }

    @Test
    public void testInvalidClass_DuplicateFieldOrder() {
        final ByteBuffer buf = toByteBuffer(HDR_EMPTY);
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(HeaderMessageWithDuplicateOrder.class, buf));
    }

    static class HeaderMessageWithNegativeOrder extends Struct {
        @Field(order = 0, type = Type.U8, padding = 1)
        short mFamily;
        @Field(order = 1, type = Type.U16)
        int mLen;
        @Field(order = 2, type = Type.S32)
        int mIfindex;
        @Field(order = 3, type = Type.U8)
        short mIcmpType;
        @Field(order = -4, type = Type.U8, padding = 6)
        short mIcmpCode;
    }

    @Test
    public void testInvalidClass_NegativeFieldOrder() {
        final ByteBuffer buf = toByteBuffer(HDR_EMPTY);
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(HeaderMessageWithNegativeOrder.class, buf));
    }

    static class HeaderMessageOutOfIndexBounds extends Struct {
        @Field(order = 0, type = Type.U8, padding = 1)
        short mFamily;
        @Field(order = 1, type = Type.U16)
        int mLen;
        @Field(order = 2, type = Type.S32)
        int mIfindex;
        @Field(order = 3, type = Type.U8)
        short mIcmpType;
        @Field(order = 5, type = Type.U8, padding = 6)
        short mIcmpCode;
    }

    @Test
    public void testInvalidClass_OutOfIndexBounds() {
        final ByteBuffer buf = toByteBuffer(HDR_EMPTY);
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(HeaderMessageOutOfIndexBounds.class, buf));
    }

    static class HeaderMessageMismatchedPrimitiveType extends Struct {
        @Field(order = 0, type = Type.U8, padding = 1)
        short mFamily;
        @Field(order = 1, type = Type.U16)
        short mLen; // should be integer
        @Field(order = 2, type = Type.S32)
        int mIfindex;
        @Field(order = 3, type = Type.U8)
        short mIcmpType;
        @Field(order = 4, type = Type.U8, padding = 6)
        short mIcmpCode;
    }

    @Test
    public void testInvalidClass_MismatchedPrimitiveDataType() {
        final ByteBuffer buf = toByteBuffer(HDR_EMPTY);
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(HeaderMessageMismatchedPrimitiveType.class, buf));
    }

    static class PrefixMessage extends Struct {
        @Field(order = 0, type = Type.BE16)
        final int mLifetime;
        @Field(order = 1, type = Type.ByteArray, arraysize = 12)
        final byte[] mPrefix;

        PrefixMessage(final int lifetime, final byte[] prefix) {
            mLifetime = lifetime;
            mPrefix = prefix;
        }
    }

    @Test
    public void testPrefixArrayField() throws Exception {
        final ByteBuffer buf = toByteBuffer(OPT_PREF64);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // The original PREF64 option message has just 12 bytes for prefix byte array
        // (Highest 96 bits of the Prefix), copyOf pads the 128-bits IPv6 address with
        // prefix and 4-bytes zeros.
        final PrefixMessage msg = Struct.parse(PrefixMessage.class, buf);
        final InetAddress addr = InetAddress.getByAddress(Arrays.copyOf(msg.mPrefix, 16));
        final IpPrefix prefix = new IpPrefix(addr, 96);
        assertEquals(10064, msg.mLifetime);
        assertTrue(prefix.equals(new IpPrefix("2001:db8:3:4:5:6::/96")));
    }

    static class HeaderMessageWithMutableField extends Struct {
        @Field(order = 0, type = Type.U8, padding = 1)
        final short mFamily;
        @Field(order = 1, type = Type.U16)
        final int mLen;
        @Field(order = 2, type = Type.S32)
        int mIfindex;
        @Field(order = 3, type = Type.U8)
        short mIcmpType;
        @Field(order = 4, type = Type.U8, padding = 6)
        final short mIcmpCode;

        HeaderMessageWithMutableField(final short family, final int len, final short code) {
            mFamily = family;
            mLen = len;
            mIcmpCode = code;
        }
    }

    @Test
    public void testMixMutableAndImmutableFields() {
        final ByteBuffer buf = toByteBuffer(HDR_EMPTY);
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(HeaderMessageWithMutableField.class, buf));
    }

    static class HeaderMsgWithStaticConstant extends Struct {
        private static final String TAG = "HeaderMessage";
        private static final int FIELD_COUNT = 5;

        @Field(order = 0, type = Type.U8, padding = 1)
        final short mFamily;
        @Field(order = 1, type = Type.U16)
        final int mLen;
        @Field(order = 2, type = Type.S32)
        final int mIfindex;
        @Field(order = 3, type = Type.U8)
        final short mIcmpType;
        @Field(order = 4, type = Type.U8, padding = 6)
        final short mIcmpCode;

        HeaderMsgWithStaticConstant(final short family, final int len, final int ifindex,
                final short type, final short code) {
            mFamily = family;
            mLen = len;
            mIfindex = ifindex;
            mIcmpType = type;
            mIcmpCode = code;
        }
    }

    @Test
    public void testStaticConstantField() {
        final HeaderMsgWithStaticConstant msg = doParsingMessageTest(HDR_EMPTY,
                HeaderMsgWithStaticConstant.class);
        assertEquals(10, msg.mFamily);
        assertEquals(0, msg.mLen);
        assertEquals(15715755, msg.mIfindex);
        assertEquals(134, msg.mIcmpType);
        assertEquals(0, msg.mIcmpCode);
    }

    static class MismatchedConstructor extends Struct {
        @Field(order = 0, type = Type.U16) final int mInt1;
        @Field(order = 1, type = Type.U16) final int mInt2;
        MismatchedConstructor(String int1, String int2) {
            mInt1 = Integer.valueOf(int1);
            mInt2 = Integer.valueOf(int2);
        }
    }

    @Test
    public void testMisMatchedConstructor() {
        final ByteBuffer buf = toByteBuffer("1234" + "5678");
        assertThrows(IllegalArgumentException.class,
                () -> Struct.parse(MismatchedConstructor.class, buf));
    }

    static class ClassWithTwoConstructors extends Struct {
        @Field(order = 0, type = Type.U16) final int mInt1;
        @Field(order = 1, type = Type.U16) final int mInt2;
        ClassWithTwoConstructors(String int1, String int2) {
            mInt1 = Integer.valueOf(int1);
            mInt2 = Integer.valueOf(int2);
        }
        ClassWithTwoConstructors(int int1, int int2) {
            mInt1 = int1;
            mInt2 = int2;
        }
    }

    @Test
    public void testClassWithTwoConstructors() {
        final ClassWithTwoConstructors msg = doParsingMessageTest("1234" + "5678",
                ClassWithTwoConstructors.class);
        assertEquals(13330 /* 0x3412 */, msg.mInt1);
        assertEquals(30806 /* 0x7856 */, msg.mInt2);
    }

    private ByteBuffer toByteBuffer(final String hexString) {
        return ByteBuffer.wrap(HexDump.hexStringToByteArray(hexString));
    }
}
