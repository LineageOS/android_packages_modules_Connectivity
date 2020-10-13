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

import android.annotation.NonNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Define a generic class that helps to parse the structured message.
 *
 * Example usage:
 *
 *    // C-style NduserOption message header definition in the kernel:
 *    struct nduseroptmsg {
 *        unsigned char nduseropt_family;
 *        unsigned char nduseropt_pad1;
 *        unsigned short nduseropt_opts_len;
 *        int nduseropt_ifindex;
 *        __u8 nduseropt_icmp_type;
 *        __u8 nduseropt_icmp_code;
 *        unsigned short nduseropt_pad2;
 *        unsigned int nduseropt_pad3;
 *    }
 *
 *    - Declare a subclass with explicit constructor or not which extends from this class to parse
 *      NduserOption header from raw bytes array.
 *
 *    - Option w/ explicit constructor:
 *      static class NduserOptHeaderMessage extends Struct {
 *          @Field(order = 0, type = Type.U8, padding = 1)
 *          final short family;
 *          @Field(order = 1, type = Type.U16)
 *          final int len;
 *          @Field(order = 2, type = Type.S32)
 *          final int ifindex;
 *          @Field(order = 3, type = Type.U8)
 *          final short type;
 *          @Field(order = 4, type = Type.U8, padding = 6)
 *          final short code;
 *
 *          NduserOptHeaderMessage(final short family, final int len, final int ifindex,
 *                  final short type, final short code) {
 *              this.family = family;
 *              this.len = len;
 *              this.ifindex = ifindex;
 *              this.type = type;
 *              this.code = code;
 *          }
 *      }
 *
 *      - Option w/o explicit constructor:
 *        static class NduserOptHeaderMessage extends Struct {
 *            @Field(order = 0, type = Type.U8, padding = 1)
 *            short family;
 *            @Field(order = 1, type = Type.U16)
 *            int len;
 *            @Field(order = 2, type = Type.S32)
 *            int ifindex;
 *            @Field(order = 3, type = Type.U8)
 *            short type;
 *            @Field(order = 4, type = Type.U8, padding = 6)
 *            short code;
 *        }
 *
 *    - Parse the target message and refer the members.
 *      final ByteBuffer buf = ByteBuffer.wrap(RAW_BYTES_ARRAY);
 *      buf.order(ByteOrder.nativeOrder());
 *      final NduserOptHeaderMessage nduserHdrMsg = Struct.parse(NduserOptHeaderMessage.class, buf);
 *      assertEquals(10, nduserHdrMsg.family);
 */
public class Struct {
    public enum Type {
        U8,        // unsigned byte,  size = 1 byte
        U16,       // unsigned short, size = 2 bytes
        U32,       // unsigned int,   size = 4 bytes
        U64,       // unsigned long,  size = 8 bytes
        S8,        // signed byte,    size = 1 byte
        S16,       // signed short,   size = 2 bytes
        S32,       // signed int,     size = 4 bytes
        S64,       // signed long,    size = 8 bytes
        BE16,      // unsigned short in network order, size = 2 bytes
        BE32,      // unsigned int in network order,   size = 4 bytes
        BE64,      // unsigned long in network order,  size = 8 bytes
        ByteArray, // byte array with predefined length
    }

    /**
     * Indicate that the field marked with this annotation will automatically be managed by this
     * class (e.g., will be parsed by #parse).
     *
     * order:     The placeholder associated with each field, consecutive order starting from zero.
     * type:      The primitive data type listed in above Type enumeration.
     * padding:   Padding bytes appear after the field for alignment.
     * arraysize: The length of byte array.
     *
     * Annotation associated with field MUST have order and type properties at least, padding
     * and arraysize properties depend on the specific usage, if these properties are absence,
     * then default value 0 will be applied.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Field {
        int order();
        Type type();
        int padding() default 0;
        int arraysize() default 0;
    }

    private static class FieldInfo {
        @NonNull
        public final Field annotation;
        @NonNull
        public final java.lang.reflect.Field field;

        FieldInfo(final Field annotation, final java.lang.reflect.Field field) {
            this.annotation = annotation;
            this.field = field;
        }
    }

    private static void checkAnnotationType(final Type type, final Class fieldType) {
        switch (type) {
            case U8:
            case S16:
                if (fieldType == Short.TYPE) return;
                break;
            case U16:
            case S32:
            case BE16:
                if (fieldType == Integer.TYPE) return;
                break;
            case U32:
            case S64:
            case BE32:
                if (fieldType == Long.TYPE) return;
                break;
            case U64:
            case BE64:
                if (fieldType == BigInteger.class || fieldType == Long.TYPE) return;
                break;
            case S8:
                if (fieldType == Byte.TYPE) return;
                break;
            case ByteArray:
                if (fieldType == byte[].class) return;
                break;
            default:
                throw new IllegalArgumentException("Unknown type" + type);
        }
        throw new IllegalArgumentException("Invalid primitive data type: " + fieldType
                + "for annotation type: " + type);
    }

    private static boolean isStructSubclass(final Class clazz) {
        return clazz != null && Struct.class.isAssignableFrom(clazz) && Struct.class != clazz;
    }

    private static int getAnnotationFieldCount(final Class clazz) {
        int count = 0;
        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Field.class)) count++;
        }
        return count;
    }

    private static boolean matchModifier(final FieldInfo[] fields, boolean immutable) {
        for (FieldInfo fi : fields) {
            if (Modifier.isFinal(fi.field.getModifiers()) != immutable) return false;
        }
        return true;
    }

    private static boolean hasBothMutableAndImmutableFields(final FieldInfo[] fields) {
        return !matchModifier(fields, true /* immutable */)
                && !matchModifier(fields, false /* mutable */);
    }

    private static boolean matchConstructor(final Constructor cons, final FieldInfo[] fields) {
        final Class[] paramTypes = cons.getParameterTypes();
        if (paramTypes.length != fields.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (!paramTypes[i].equals(fields[i].field.getType())) return false;
        }
        return true;
    }

    private static BigInteger readBigInteger(final ByteBuffer buf) {
        // The magnitude argument of BigInteger constructor is a byte array in big-endian order.
        // If ByteBuffer is read in little-endian, reverse the order of the bytes is required;
        // if ByteBuffer is read in big-endian, then just keep it as-is.
        final byte[] input = new byte[8];
        for (int i = 0; i < 8; i++) {
            input[(buf.order() == ByteOrder.LITTLE_ENDIAN ? input.length - 1 - i : i)] = buf.get();
        }
        return new BigInteger(1, input);
    }

    private static Object getFieldValue(final ByteBuffer buf, final FieldInfo fieldInfo)
            throws BufferUnderflowException {
        final Object value;
        checkAnnotationType(fieldInfo.annotation.type(), fieldInfo.field.getType());
        switch (fieldInfo.annotation.type()) {
            case U8:
                value = (short) (buf.get() & 0xFF);
                break;
            case U16:
                value = (int) (buf.getShort() & 0xFFFF);
                break;
            case U32:
                value = (long) (buf.getInt() & 0xFFFFFFFFL);
                break;
            case U64:
                if (fieldInfo.field.getType() == BigInteger.class) {
                    value = readBigInteger(buf);
                } else {
                    value = buf.getLong();
                }
                break;
            case S8:
                value = buf.get();
                break;
            case S16:
                value = buf.getShort();
                break;
            case S32:
                value = buf.getInt();
                break;
            case S64:
                value = buf.getLong();
                break;
            case BE16:
                if (buf.order() == ByteOrder.LITTLE_ENDIAN) {
                    value = (int) (Short.reverseBytes(buf.getShort()) & 0xFFFF);
                } else {
                    value = (int) (buf.getShort() & 0xFFFF);
                }
                break;
            case BE32:
                if (buf.order() == ByteOrder.LITTLE_ENDIAN) {
                    value = (long) (Integer.reverseBytes(buf.getInt()) & 0xFFFFFFFFL);
                } else {
                    value = (long) (buf.getInt() & 0xFFFFFFFFL);
                }
                break;
            case BE64:
                if (fieldInfo.field.getType() == BigInteger.class) {
                    value = readBigInteger(buf);
                } else {
                    if (buf.order() == ByteOrder.LITTLE_ENDIAN) {
                        value = Long.reverseBytes(buf.getLong());
                    } else {
                        value = buf.getLong();
                    }
                }
                break;
            case ByteArray:
                final byte[] array = new byte[fieldInfo.annotation.arraysize()];
                buf.get(array);
                value = array;
                break;
            default:
                throw new IllegalArgumentException("Unknown type:" + fieldInfo.annotation.type());
        }

        // Skip the padding data for alignment if any.
        if (fieldInfo.annotation.padding() > 0) {
            buf.position(buf.position() + fieldInfo.annotation.padding());
        }
        return value;
    }

    private static FieldInfo[] getClassFieldInfo(final Class clazz) {
        if (!isStructSubclass(clazz)) {
            throw new IllegalArgumentException(clazz.getName() + " is not a subclass of "
                    + Struct.class.getName());
        }

        final FieldInfo[] annotationFields = new FieldInfo[getAnnotationFieldCount(clazz)];

        // Since array returned from Class#getDeclaredFields doesn't guarantee the actual order
        // of field appeared in the class, that is a problem when parsing raw data read from
        // ByteBuffer. Store the fields appeared by the order() defined in the Field annotation.
        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;

            final Field annotation = field.getAnnotation(Field.class);
            if (annotation == null) {
                throw new IllegalArgumentException("Field " + field.getName()
                        + " is missing the " + Field.class.getSimpleName()
                        + " annotation");
            }
            if (annotation.order() < 0 || annotation.order() >= annotationFields.length) {
                throw new IllegalArgumentException("Annotation order: " + annotation.order()
                        + " is negative or non-consecutive");
            }
            if (annotationFields[annotation.order()] != null) {
                throw new IllegalArgumentException("Duplicated annotation order: "
                        + annotation.order());
            }
            annotationFields[annotation.order()] = new FieldInfo(annotation, field);
        }
        return annotationFields;
    }

    /**
     * Parse raw data from ByteBuffer according to the pre-defined annotation rule and return
     * the type-variable object which is subclass of Struct class.
     *
     * TODO:
     * 1. Support subclass inheritance.
     * 2. Introduce annotation processor to enforce the subclass naming schema.
     */
    public static <T> T parse(final Class<T> clazz, final ByteBuffer buf) {
        try {
            final FieldInfo[] foundFields = getClassFieldInfo(clazz);
            if (hasBothMutableAndImmutableFields(foundFields)) {
                throw new IllegalArgumentException("Class has both immutable and mutable fields");
            }

            Constructor<?> constructor = null;
            Constructor<?> defaultConstructor = null;
            final Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            for (Constructor cons : constructors) {
                if (matchConstructor(cons, foundFields)) constructor = cons;
                if (cons.getParameterTypes().length == 0) defaultConstructor = cons;
            }

            if (constructor == null && defaultConstructor == null) {
                throw new IllegalArgumentException("Fail to find available constructor");
            }
            if (constructor != null) {
                final Object[] args = new Object[foundFields.length];
                for (int i = 0; i < args.length; i++) {
                    args[i] = getFieldValue(buf, foundFields[i]);
                }
                return (T) constructor.newInstance(args);
            }

            final Object instance = defaultConstructor.newInstance();
            for (FieldInfo fi : foundFields) {
                fi.field.set(instance, getFieldValue(buf, fi));
            }
            return (T) instance;
        } catch (IllegalAccessException
                | InvocationTargetException
                | InstantiationException e) {
            throw new IllegalArgumentException("Fail to create a instance from constructor", e);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Fail to read raw data from ByteBuffer", e);
        }
    }
}
