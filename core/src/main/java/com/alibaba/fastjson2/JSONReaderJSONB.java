package com.alibaba.fastjson2;

import com.alibaba.fastjson2.reader.*;
import com.alibaba.fastjson2.util.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static com.alibaba.fastjson2.JSONB.Constants.*;
import static com.alibaba.fastjson2.JSONB.typeName;
import static com.alibaba.fastjson2.JSONFactory.*;
import static com.alibaba.fastjson2.JSONReaderUTF8.char2_utf8;
import static com.alibaba.fastjson2.util.DateUtils.DEFAULT_ZONE_ID;
import static com.alibaba.fastjson2.util.DateUtils.SHANGHAI_ZONE_ID;
import static com.alibaba.fastjson2.util.IOUtils.getInt;
import static com.alibaba.fastjson2.util.JDKUtils.*;
import static com.alibaba.fastjson2.util.TypeUtils.toBigDecimal;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

final class JSONReaderJSONB
        extends JSONReader {
    static final long BASE = UNSAFE.arrayBaseOffset(byte[].class);

    static final byte[] SHANGHAI_ZONE_ID_NAME_BYTES = JSONB.toBytes(DateUtils.SHANGHAI_ZONE_ID_NAME);
    static Charset GB18030;

    static final byte[] FIXED_TYPE_SIZE = {
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 0,
            3, 3, 3, 3, 3, 3, 3, 0, 5, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
            24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
            40, 41, 42, 43, 44, 45, 46, 47, 0, -1, -1, -1, -1, -1, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 5, 0, 0, 1,
            1, 1, 0, 0, 0, 9, 0, 5, 0, 0, 0, 0, 3, 2, 9, 5,
            3, 3, 3, 3, 3, 3, 3, 0, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 0, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
    };

    protected final byte[] bytes;
    protected final int length;
    protected final int end;

    protected byte type;
    protected int strlen;
    protected byte strtype;
    protected int strBegin;

    protected byte[] valueBytes;
    protected char[] charBuf;
    protected final CacheItem cacheItem;

    protected final SymbolTable symbolTable;

    protected long symbol0Hash;
    protected int symbol0Begin;
    protected int symbol0Length;
    protected byte symbol0StrType;

    protected long[] symbols;

    JSONReaderJSONB(Context ctx, InputStream is) {
        super(ctx, true);

        int cacheIndex = System.identityHashCode(Thread.currentThread()) & (CACHE_ITEMS.length - 1);
        cacheItem = CACHE_ITEMS[cacheIndex];
        byte[] bytes = BYTES_UPDATER.getAndSet(cacheItem, null);
        if (bytes == null) {
            bytes = new byte[ctx.bufferSize];
        }

        int off = 0;
        try {
            for (; ; ) {
                int n = is.read(bytes, off, bytes.length - off);
                if (n == -1) {
                    break;
                }
                off += n;

                if (off == bytes.length) {
                    bytes = Arrays.copyOf(bytes, bytes.length + ctx.bufferSize);
                }
            }
        } catch (IOException ioe) {
            throw new JSONException("read error", ioe);
        }

        this.bytes = bytes;
        this.offset = 0;
        this.length = off;
        this.end = length;
        this.symbolTable = ctx.symbolTable;
    }

    JSONReaderJSONB(Context ctx, byte[] bytes, int off, int length) {
        super(ctx, true);
        this.bytes = bytes;
        this.offset = off;
        this.length = length;
        this.end = off + length;
        this.symbolTable = ctx.symbolTable;
        this.cacheItem = CACHE_ITEMS[System.identityHashCode(Thread.currentThread()) & (CACHE_ITEMS.length - 1)];
    }

    String getLatin1String(int offset, int length) {
        if (JDKUtils.ANDROID_SDK_INT >= 34) {
            return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
        }

        char[] charBuf = this.charBuf;
        if (charBuf == null) {
            this.charBuf = charBuf = CHARS_UPDATER.getAndSet(cacheItem, null);
        }
        if (charBuf == null || charBuf.length < length) {
            this.charBuf = charBuf = new char[length];
        }
        for (int i = 0; i < length; i++) {
            charBuf[i] = (char) (bytes[offset + i] & 0xFF);
        }
        return new String(charBuf, 0, length);
    }

    @Override
    public String getString() {
        if (strtype == BC_NULL) {
            return null;
        }

        if (strlen < 0) {
            return symbolTable.getName(-strlen);
        }

        Charset charset;
        if (strtype == BC_STR_ASCII || strtype >= BC_STR_ASCII_FIX_MIN && strtype <= BC_STR_ASCII_FIX_MAX) {
            charset = StandardCharsets.ISO_8859_1;

            if (ANDROID_SDK_INT < 34) {
                int strOff = this.strBegin;
                char[] charBuf = this.charBuf;
                if (charBuf == null) {
                    this.charBuf = charBuf = CHARS_UPDATER.getAndSet(cacheItem, null);
                }
                if (charBuf == null || charBuf.length < strlen) {
                    this.charBuf = charBuf = new char[strlen];
                }

                for (int i = 0; i < strlen; i++) {
                    charBuf[i] = (char) (bytes[strOff + i] & 0xff);
                }
                return new String(charBuf, 0, strlen);
            }
        } else if (strtype == BC_STR_UTF8) {
            charset = StandardCharsets.UTF_8;
        } else if (strtype == BC_STR_UTF16) {
            charset = StandardCharsets.UTF_16;
        } else if (strtype == BC_STR_UTF16LE) {
            charset = StandardCharsets.UTF_16LE;
        } else if (strtype == BC_STR_UTF16BE) {
            charset = StandardCharsets.UTF_16BE;
        } else {
            throw notSupportType(strtype);
        }

        return new String(bytes, strBegin, strlen, charset);
    }

    public int readLength() {
        byte type = bytes[offset++];
        if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
            return type;
        }

        if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
            return ((type - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
        }

        if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
            int len = getInt3(bytes, offset, type);
            offset += 2;
            return len;
        }

        if (type == BC_INT32) {
            int len = getInt(bytes, offset);
            offset += 4;
            if (len > 1024 * 1024 * 256) {
                throw new JSONException("input length overflow");
            }
            return len;
        }

        throw notSupportType(type);
    }

    static int getInt3(byte[] bytes, int offset, int type) {
        return ((type - BC_INT32_SHORT_ZERO) << 16)
                + ((bytes[offset] & 0xFF) << 8)
                + (bytes[offset + 1] & 0xFF);
    }

    @Override
    public boolean isArray() {
        if (offset >= bytes.length) {
            return false;
        }

        byte type = bytes[offset];
        return type >= BC_ARRAY_FIX_MIN && type <= BC_ARRAY;
    }

    @Override
    public boolean isObject() {
        return offset < end && bytes[offset] == BC_OBJECT;
    }

    @Override
    public boolean isNumber() {
        byte type = bytes[offset];
        return type >= BC_DOUBLE_NUM_0 && type <= BC_INT32;
    }

    @Override
    public boolean isString() {
        return offset < bytes.length
                && (type = bytes[offset]) >= BC_STR_ASCII_FIX_MIN;
    }

    @Override
    public boolean nextIfMatch(char ch) {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfArrayStart() {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfArrayEnd() {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfComma() {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfObjectStart() {
        if (bytes[offset] != BC_OBJECT) {
            return false;
        }
        offset++;
        return true;
    }

    @Override
    public boolean nextIfObjectEnd() {
        if (bytes[offset] != BC_OBJECT_END) {
            return false;
        }
        offset++;
        return true;
    }

    @Override
    public boolean nextIfNullOrEmptyString() {
        if (bytes[offset] == BC_NULL) {
            offset++;
            return true;
        }

        if (bytes[offset] != BC_STR_ASCII_FIX_MIN) {
            return false;
        }
        offset += 1;
        return true;
    }

    @Override
    public <T> T read(Type type) {
        boolean fieldBased = (context.features & Feature.FieldBased.mask) != 0;
        ObjectReader objectReader = context.provider.getObjectReader(type, fieldBased);
        return (T) objectReader.readJSONBObject(this, null, null, 0);
    }

    @Override
    public <T> T read(Class<T> type) {
        boolean fieldBased = (context.features & Feature.FieldBased.mask) != 0;
        ObjectReader objectReader = context.provider.getObjectReader(type, fieldBased);
        return (T) objectReader.readJSONBObject(this, null, null, 0);
    }

    @Override
    public Map<String, Object> readObject() {
        final byte[] bytes = this.bytes;
        final long features = context.features;
        type = bytes[offset++];
        if (type == BC_NULL) {
            return null;
        }

        if (type >= BC_OBJECT) {
            Map map;
            if ((features & Feature.UseNativeObject.mask) != 0) {
                map = new HashMap();
            } else if (context.objectSupplier != null) {
                map = context.objectSupplier.get();
            } else {
                map = new JSONObject();
            }

            while (true) {
                final byte type = bytes[offset];
                if (type == BC_OBJECT_END) {
                    offset++;
                    break;
                }

                Object name;
                if (type >= BC_STR_ASCII_FIX_MIN) {
                    name = readFieldName();
                } else {
                    name = readAny();
                }

                final byte valueType = bytes[offset];
                if (valueType == BC_REFERENCE) {
                    String reference = readReference();
                    if ("..".equals(reference)) {
                        map.put(name, map);
                    } else {
                        addResolveTask(map, name, JSONPath.of(reference));
                    }
                    continue;
                }

                Object value;
                if (valueType >= BC_STR_ASCII_FIX_MIN && valueType <= BC_STR_GB18030) {
                    value = readString();
                } else if (valueType >= BC_INT32_NUM_MIN && valueType <= BC_INT32_NUM_MAX) {
                    offset++;
                    value = (int) valueType;
                } else if (valueType == BC_TRUE) {
                    offset++;
                    value = Boolean.TRUE;
                } else if (valueType == BC_FALSE) {
                    offset++;
                    value = Boolean.FALSE;
                } else if (valueType == BC_OBJECT) {
                    value = readObject();
                } else if (valueType == BC_INT64) {
                    offset++;
                    long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                    offset += 8;
                    value = BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value);
                } else if (valueType >= BC_ARRAY_FIX_MIN && valueType <= BC_ARRAY) {
                    offset++;
                    int len;
                    if (valueType == BC_ARRAY) {
                        byte itemType = bytes[offset];
                        if (itemType >= BC_INT32_NUM_MIN && itemType <= BC_INT32_NUM_MAX) {
                            offset++;
                            len = itemType;
                        } else if (itemType >= BC_INT32_BYTE_MIN && itemType <= BC_INT32_BYTE_MAX) {
                            len = ((itemType - BC_INT32_BYTE_ZERO) << 8) + (bytes[offset + 1] & 0xFF);
                            offset += 2;
                        } else {
                            len = readLength();
                        }
                    } else {
                        len = valueType - BC_ARRAY_FIX_MIN;
                    }

                    if (len == 0) {
                        if ((features & Feature.UseNativeObject.mask) != 0) {
                            value = new ArrayList<>();
                        } else {
                            if (context.arraySupplier != null) {
                                value = context.arraySupplier.get();
                            } else {
                                value = new JSONArray();
                            }
                        }
                    } else {
                        List list;
                        if ((features & Feature.UseNativeObject.mask) != 0) {
                            list = new ArrayList(len);
                        } else if (context.arraySupplier != null) {
                            list = context.arraySupplier.get();
                        } else {
                            list = new JSONArray(len);
                        }

                        for (int i = 0; i < len; ++i) {
                            byte itemType = bytes[offset];
                            if (itemType == BC_REFERENCE) {
                                String reference = readReference();
                                if ("..".equals(reference)) {
                                    list.add(list);
                                } else {
                                    list.add(null);
                                    addResolveTask(list, i, JSONPath.of(reference));
                                }
                                continue;
                            }

                            Object item;
                            if (itemType >= BC_STR_ASCII_FIX_MIN && itemType <= BC_STR_GB18030) {
                                item = readString();
                            } else if (itemType == BC_OBJECT) {
                                item = readObject();
                            } else {
                                item = readAny();
                            }
                            list.add(item);
                        }
                        value = list;
                    }
                } else if (valueType >= BC_INT32_BYTE_MIN && valueType <= BC_INT32_BYTE_MAX) {
                    value = ((valueType - BC_INT32_BYTE_ZERO) << 8)
                            + (bytes[offset + 1] & 0xFF);
                    offset += 2;
                } else if (valueType >= BC_INT32_SHORT_MIN && valueType <= BC_INT32_SHORT_MAX) {
                    int int32Value = getInt3(bytes, offset + 1, valueType);
                    offset += 3;
                    value = new Integer(int32Value);
                } else if (valueType == BC_INT32) {
                    int int32Value = getInt(bytes, offset + 1);
                    offset += 5;
                    value = new Integer(int32Value);
                } else {
                    value = readAny();
                }

                if (value == null && (features & Feature.IgnoreNullPropertyValue.mask) != 0) {
                    continue;
                }

                map.put(name, value);
            }

            return map;
        }

        if (type == BC_TYPED_ANY) {
            ObjectReader objectReader = checkAutoType(Map.class, 0, 0);
            return (Map) objectReader.readObject(this, null, null, 0);
        }

        throw notSupportType(type);
    }

    public void read(final Map map, long features) {
        if (bytes[offset] != BC_OBJECT) {
            throw new JSONException("object not support input " + error(type));
        }
        offset++;

        for (int i = 0; ; ++i) {
            byte type = bytes[offset];
            if (type == BC_OBJECT_END) {
                offset++;
                break;
            }

            Object name;
            if (type >= BC_STR_ASCII_FIX_MIN) {
                name = readFieldName();
            } else {
                name = readAny();
            }

            if (isReference()) {
                String reference = readReference();
                if ("..".equals(reference)) {
                    map.put(name, map);
                } else {
                    addResolveTask(map, name, JSONPath.of(reference));
                    map.put(name, null);
                }
                continue;
            }

            byte valueType = bytes[offset];
            Object value;
            if (valueType >= BC_STR_ASCII_FIX_MIN && valueType <= BC_STR_GB18030) {
                value = readString();
            } else if (valueType >= BC_INT32_NUM_MIN && valueType <= BC_INT32_NUM_MAX) {
                offset++;
                value = (int) valueType;
            } else if (valueType == BC_TRUE) {
                offset++;
                value = Boolean.TRUE;
            } else if (valueType == BC_FALSE) {
                offset++;
                value = Boolean.FALSE;
            } else if (valueType == BC_OBJECT) {
                value = readObject();
            } else {
                value = readAny();
            }

            if (value == null && (context.features & Feature.IgnoreNullPropertyValue.mask) != 0) {
                continue;
            }

            map.put(name, value);
        }
    }

    @Override
    public Object readAny() {
        if (offset >= bytes.length) {
            throw new JSONException("readAny overflow : " + offset + "/" + bytes.length);
        }

        type = bytes[offset++];
        switch (type) {
            case BC_NULL:
                return null;
            case BC_TRUE:
                return true;
            case BC_FALSE:
                return false;
            case BC_INT8:
                return bytes[offset++];
            case BC_INT16:
                return (short) ((bytes[offset++] << 8)
                        + (bytes[offset++] & 0xFF));
            case BC_INT32: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return new Integer(int32Value);
            }
            case BC_INT64_INT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return new Long(int32Value);
            }
            case BC_INT64: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return Long.valueOf(
                        BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value)
                );
            }
            case BC_BIGINT: {
                int len = readInt32Value();
                byte[] bytes = new byte[len];
                System.arraycopy(this.bytes, offset, bytes, 0, len);
                offset += len;
                return new BigInteger(bytes);
            }
            case BC_FLOAT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return Float.intBitsToFloat(int32Value);
            }
            case BC_FLOAT_INT: {
                return (float) readInt32Value();
            }
            case BC_DOUBLE: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return Double.longBitsToDouble(
                        BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value)
                );
            }
            case BC_DOUBLE_LONG: {
                return (double) readInt64Value();
            }
            case BC_STR_UTF8: {
                int strlen = readLength();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                // empty string to null
                if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
                    str = null;
                }
                offset += strlen;
                return str;
            }
            case BC_STR_UTF16: {
                int strlen = readLength();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16);
                // empty string to null
                if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
                    str = null;
                }
                offset += strlen;
                return str;
            }
            case BC_STR_UTF16LE: {
                int strlen = readLength();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16LE);
                // empty string to null
                if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
                    str = null;
                }
                offset += strlen;
                return str;
            }
            case BC_STR_UTF16BE: {
                int strlen = readLength();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16BE);
                // empty string to null
                if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
                    str = null;
                }
                offset += strlen;
                return str;
            }
            case BC_STR_GB18030: {
                if (GB18030 == null) {
                    GB18030 = Charset.forName("GB18030");
                }
                int strlen = readLength();
                String str = new String(bytes, offset, strlen, GB18030);
                // empty string to null
                if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
                    str = null;
                }
                offset += strlen;
                return str;
            }
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                BigDecimal decimal;
                if (scale == 0) {
                    decimal = new BigDecimal(unscaledValue);
                } else {
                    decimal = new BigDecimal(unscaledValue, scale);
                }
                return decimal;
            }
            case BC_DECIMAL_LONG: {
                return BigDecimal.valueOf(
                        readInt64Value()
                );
            }
            case BC_BINARY: {
                int len = readLength();
                byte[] binary = Arrays.copyOfRange(this.bytes, offset, offset + len);
                offset += len;
                return binary;
            }
            case BC_TIMESTAMP_MINUTES: {
                long minutes = getInt(bytes, offset);
                offset += 4;
                return new Date(minutes * 60L * 1000L);
            }
            case BC_TIMESTAMP_SECONDS: {
                long seconds = getInt(bytes, offset);
                offset += 4;
                return new Date(seconds * 1000);
            }
            case BC_LOCAL_DATE: {
                int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
                byte month = bytes[offset++];
                byte dayOfMonth = bytes[offset++];
                return LocalDate.of(year, month, dayOfMonth);
            }
            case BC_LOCAL_TIME: {
                byte hour = bytes[offset++];
                byte minute = bytes[offset++];
                byte second = bytes[offset++];
                int nano = readInt32Value();
                return LocalTime.of(hour, minute, second, nano);
            }
            case BC_LOCAL_DATETIME: {
                int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
                byte month = bytes[offset++];
                byte dayOfMonth = bytes[offset++];
                byte hour = bytes[offset++];
                byte minute = bytes[offset++];
                byte second = bytes[offset++];
                int nano = readInt32Value();
                return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nano);
            }
            case BC_TIMESTAMP_WITH_TIMEZONE: {
                return readTimestampWithTimeZone();
            }
            case BC_TIMESTAMP: {
                long epochSeconds = readInt64Value();
                int nano = readInt32Value();
                return Instant.ofEpochSecond(epochSeconds, nano);
            }
            case BC_TIMESTAMP_MILLIS: {
                long millis = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return new Date(
                        BIG_ENDIAN ? millis : Long.reverseBytes(millis)
                );
            }
            case BC_BIGINT_LONG: {
                return BigInteger.valueOf(
                        readInt64Value()
                );
            }
            case BC_TYPED_ANY: {
                long typeHash = readTypeHashCode();

                if (context.autoTypeBeforeHandler != null) {
                    Class<?> filterClass = context.autoTypeBeforeHandler.apply(typeHash, null, context.features);

                    if (filterClass == null) {
                        String typeName = getString();
                        filterClass = context.autoTypeBeforeHandler.apply(typeName, null, context.features);
                    }

                    if (filterClass != null) {
                        ObjectReader autoTypeObjectReader = context.getObjectReader(filterClass);
                        return autoTypeObjectReader.readJSONBObject(this, null, null, 0);
                    }
                }

                boolean supportAutoType = (context.features & Feature.SupportAutoType.mask) != 0;
                if (!supportAutoType) {
                    if (isObject()) {
                        return readObject();
                    }

                    if (isArray()) {
                        return readArray();
                    }

                    throw new JSONException("autoType not support , offset " + offset + "/" + bytes.length);
                }

                ObjectReader autoTypeObjectReader = context.getObjectReaderAutoType(typeHash);
                if (autoTypeObjectReader == null) {
                    String typeName = getString();
                    autoTypeObjectReader = context.getObjectReaderAutoType(typeName, null);

                    if (autoTypeObjectReader == null) {
                        throw new JSONException("autoType not support : " + typeName + ", offset " + offset + "/" + bytes.length);
                    }
                }
                return autoTypeObjectReader.readJSONBObject(this, null, null, 0);
            }
            case BC_DOUBLE_NUM_0:
                return 0D;
            case BC_DOUBLE_NUM_1:
                return 1D;
            case BC_CHAR:
                int intValue = readInt32Value();
                return (char) intValue;
            case BC_OBJECT: {
                Map map = null;
                boolean supportAutoType = (context.features & Feature.SupportAutoType.mask) != 0;
                for (int i = 0; ; ++i) {
                    byte type = bytes[offset];
                    if (type == BC_OBJECT_END) {
                        offset++;
                        break;
                    }

                    Object name;
                    if (supportAutoType && i == 0 && type >= BC_STR_ASCII_FIX_MIN) {
                        long hash = readFieldNameHashCode();

                        if (hash == ObjectReader.HASH_TYPE) {
                            long typeHash = readValueHashCode();
                            ObjectReader autoTypeObjectReader = context.getObjectReaderAutoType(typeHash);
                            if (autoTypeObjectReader == null) {
                                String typeName = getString();
                                autoTypeObjectReader = context.getObjectReaderAutoType(typeName, null);

                                if (autoTypeObjectReader == null) {
                                    throw new JSONException("auotype not support : " + typeName + ", offset " + offset + "/" + bytes.length);
                                }
                            }

                            typeRedirect = true;
                            return autoTypeObjectReader.readJSONBObject(this, null, null, 0);
                        }
                        name = getString();
                    } else {
                        if (type >= BC_STR_ASCII_FIX_MIN) {
                            name = readFieldName();
                        } else {
                            name = readAny();
                        }
                    }

                    if (map == null) {
                        if ((context.features & Feature.UseNativeObject.mask) != 0) {
                            map = new HashMap();
                        } else if (context.objectSupplier != null) {
                            map = context.objectSupplier.get();
                        } else {
                            map = new JSONObject();
                        }
                    }

                    if (isReference()) {
                        String reference = readReference();
                        if ("..".equals(reference)) {
                            map.put(name, map);
                        } else {
                            addResolveTask(map, name, JSONPath.of(reference));
                            map.put(name, null);
                        }
                        continue;
                    }

                    byte valueType = bytes[offset];
                    Object value;
                    if (valueType >= BC_STR_ASCII_FIX_MIN && valueType <= BC_STR_GB18030) {
                        value = readString();
                    } else if (valueType >= BC_INT32_NUM_MIN && valueType <= BC_INT32_NUM_MAX) {
                        offset++;
                        value = (int) valueType;
                    } else if (valueType == BC_TRUE) {
                        offset++;
                        value = Boolean.TRUE;
                    } else if (valueType == BC_FALSE) {
                        offset++;
                        value = Boolean.FALSE;
                    } else if (valueType == BC_OBJECT) {
                        value = readObject();
                    } else {
                        value = readAny();
                    }

                    if (value == null && (context.features & Feature.IgnoreNullPropertyValue.mask) != 0) {
                        continue;
                    }

                    map.put(name, value);
                }

                if (map == null) {
                    if ((context.features & Feature.UseNativeObject.mask) != 0) {
                        map = new HashMap();
                    } else {
                        map = new JSONObject();
                    }
                }
                return map;
            }
            default:
                if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                    return (int) type;
                }

                if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                    return ((type - BC_INT32_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
                    int int3 = getInt3(bytes, offset, type);
                    offset += 2;
                    return int3;
                }

                if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
                    return (long) INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
                }

                if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
                    return (long) ((type - BC_INT64_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
                    return (long) (((type - BC_INT64_SHORT_ZERO) << 16)
                            + ((bytes[offset++] & 0xFF) << 8)
                            + (bytes[offset++] & 0xFF));
                }

                if (type >= BC_ARRAY_FIX_MIN && type <= BC_ARRAY) {
                    int len = type == BC_ARRAY
                            ? readLength()
                            : type - BC_ARRAY_FIX_MIN;

                    if (len == 0) {
                        if ((context.features & Feature.UseNativeObject.mask) != 0) {
                            return new ArrayList<>();
                        } else {
                            if (context.arraySupplier != null) {
                                return context.arraySupplier.get();
                            }
                            return new JSONArray();
                        }
                    }

                    List list;
                    if ((context.features & Feature.UseNativeObject.mask) != 0) {
                        list = new ArrayList(len);
                    } else {
                        list = new JSONArray(len);
                    }

                    for (int i = 0; i < len; ++i) {
                        if (isReference()) {
                            String reference = readReference();
                            if ("..".equals(reference)) {
                                list.add(list);
                            } else {
                                list.add(null);
                                addResolveTask(list, i, JSONPath.of(reference));
                            }
                            continue;
                        }

                        Object item = readAny();
                        list.add(item);
                    }
                    return list;
                }

                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII) {
                    strlen = type == BC_STR_ASCII
                            ? readLength()
                            : type - BC_STR_ASCII_FIX_MIN;

                    if (strlen < 0) {
                        return symbolTable.getName(-strlen);
                    }

                    String str = getLatin1String(offset, strlen);
                    offset += strlen;

                    if ((context.features & Feature.TrimString.mask) != 0) {
                        str = str.trim();
                    }

                    if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
                        str = null;
                    }
                    return str;
                }

                if (type == BC_SYMBOL) {
                    strlen = readLength();

                    if (strlen >= 0) {
                        throw new JSONException("not support symbol : " + strlen);
                    }

                    return symbolTable.getName(-strlen);
                }

                throw new JSONException("not support type : " + error(type));
        }
    }

    private ZonedDateTime readTimestampWithTimeZone() {
        byte[] bytes = this.bytes;
        int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
        byte month = bytes[offset++];
        byte dayOfMonth = bytes[offset++];
        byte hour = bytes[offset++];
        byte minute = bytes[offset++];
        byte second = bytes[offset++];
        int nano = readInt32Value();
        // SHANGHAI_ZONE_ID_NAME_BYTES
        ZoneId zoneId;
        {
            boolean shanghai;
            byte[] shanghaiZoneIdNameBytes = SHANGHAI_ZONE_ID_NAME_BYTES;
            if (offset + shanghaiZoneIdNameBytes.length < bytes.length) {
                shanghai = true;
                for (int i = 0; i < shanghaiZoneIdNameBytes.length; ++i) {
                    if (bytes[offset + i] != shanghaiZoneIdNameBytes[i]) {
                        shanghai = false;
                        break;
                    }
                }
            } else {
                shanghai = false;
            }
            if (shanghai) {
                offset += shanghaiZoneIdNameBytes.length;
                zoneId = SHANGHAI_ZONE_ID;
            } else {
                String zoneIdStr = readString();
                zoneId = DateUtils.getZoneId(zoneIdStr, SHANGHAI_ZONE_ID);
            }
        }
        LocalDateTime ldt = LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nano);
        return ZonedDateTime.of(ldt, zoneId);
    }

    @Override
    public byte getType() {
        return bytes[offset];
    }

    @Override
    public List readArray() {
        int entryCnt = startArray();
        JSONArray array = new JSONArray(entryCnt);
        for (int i = 0; i < entryCnt; i++) {
            byte valueType = bytes[offset];
            Object value;
            if (valueType >= BC_STR_ASCII_FIX_MIN && valueType <= BC_STR_GB18030) {
                value = readString();
            } else if (valueType >= BC_INT32_NUM_MIN && valueType <= BC_INT32_NUM_MAX) {
                offset++;
                value = (int) valueType;
            } else if (valueType == BC_TRUE) {
                offset++;
                value = Boolean.TRUE;
            } else if (valueType == BC_FALSE) {
                offset++;
                value = Boolean.FALSE;
            } else if (valueType == BC_OBJECT) {
                value = readObject();
            } else if (valueType == BC_INT64) {
                offset++;
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                value = BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value);
            } else if (valueType >= BC_ARRAY_FIX_MIN && valueType <= BC_ARRAY) {
                offset++;
                int len = valueType == BC_ARRAY
                        ? readLength()
                        : valueType - BC_ARRAY_FIX_MIN;

                if (len == 0) {
                    if ((context.features & Feature.UseNativeObject.mask) != 0) {
                        value = new ArrayList<>();
                    } else {
                        if (context.arraySupplier != null) {
                            value = context.arraySupplier.get();
                        } else {
                            value = new JSONArray();
                        }
                    }
                } else {
                    List list;
                    if ((context.features & Feature.UseNativeObject.mask) != 0) {
                        list = new ArrayList(len);
                    } else {
                        list = new JSONArray(len);
                    }

                    for (int j = 0; j < len; ++j) {
                        if (isReference()) {
                            String reference = readReference();
                            if ("..".equals(reference)) {
                                list.add(list);
                            } else {
                                list.add(null);
                                addResolveTask(list, j, JSONPath.of(reference));
                            }
                            continue;
                        }

                        byte itemType = bytes[offset];
                        Object item;
                        if (itemType >= BC_STR_ASCII_FIX_MIN && itemType <= BC_STR_GB18030) {
                            item = readString();
                        } else if (itemType == BC_OBJECT) {
                            item = readObject();
                        } else {
                            item = readAny();
                        }
                        list.add(item);
                    }
                    value = list;
                }
            } else if (valueType >= BC_INT32_BYTE_MIN && valueType <= BC_INT32_BYTE_MAX) {
                value = ((valueType - BC_INT32_BYTE_ZERO) << 8)
                        + (bytes[offset + 1] & 0xFF);
                offset += 2;
            } else if (valueType >= BC_INT32_SHORT_MIN && valueType <= BC_INT32_SHORT_MAX) {
                int int3 = getInt3(bytes, offset + 1, valueType);
                offset += 3;
                value = new Integer(int3);
            } else if (valueType == BC_INT32) {
                int int32Value = getInt(bytes, offset + 1);
                offset += 5;
                value = new Integer(int32Value);
            } else if (valueType == BC_REFERENCE) {
                String reference = readReference();
                if ("..".equals(reference)) {
                    value = array;
                } else {
                    addResolveTask(array, i, JSONPath.of(reference));
                    continue;
                }
            } else {
                value = readAny();
            }
            array.add(value);
        }
        return array;
    }

    @Override
    public List readArray(Type itemType) {
        if (nextIfNull()) {
            return null;
        }

        if (bytes[offset] == BC_TYPED_ANY) {
            Object obj = readAny();
            if (obj instanceof List) {
                return (List) obj;
            }
            if (obj instanceof Collection) {
                return new JSONArray((Collection) obj);
            }
            throw new JSONException("not support class " + obj.getClass());
        }

        int entryCnt = startArray();
        JSONArray array = new JSONArray(entryCnt);
        for (int i = 0; i < entryCnt; i++) {
            array.add(read(itemType));
        }
        return array;
    }

    @Override
    public List readList(Type[] types) {
        if (nextIfNull()) {
            return null;
        }

        int entryCnt = startArray();
        JSONArray array = new JSONArray(entryCnt);
        for (int i = 0; i < entryCnt; i++) {
            Type itemType = types[i];
            array.add(read(itemType));
        }
        return array;
    }

    @Override
    public byte[] readHex() {
        String str = readString();
        byte[] bytes = new byte[str.length() / 2];
        for (int i = 0; i < bytes.length; ++i) {
            char c0 = str.charAt(i * 2);
            char c1 = str.charAt(i * 2 + 1);

            int b0 = c0 - (c0 <= 57 ? 48 : 55);
            int b1 = c1 - (c1 <= 57 ? 48 : 55);
            bytes[i] = (byte) ((b0 << 4) | b1);
        }
        return bytes;
    }

    @Override
    public boolean isReference() {
        return offset < bytes.length && bytes[offset] == BC_REFERENCE;
    }

    @Override
    public String readReference() {
        if (bytes[offset] != BC_REFERENCE) {
            return null;
        }
        offset++;
        if (isString()) {
            return readString();
        }

        throw new JSONException("reference not support input " + error(type));
    }

    @Override
    public ObjectReader checkAutoType(Class expectClass, long expectClassHash, long features) {
        ObjectReader autoTypeObjectReader = null;
        byte type = bytes[offset];
        if (type == BC_TYPED_ANY) {
            offset++;

            final long typeHash = readTypeHashCode();
            ObjectReaderProvider provider = context.provider;

            if (expectClassHash == typeHash) {
                ObjectReader objectReader = context.getObjectReader(expectClass);
                Class objectClass = objectReader.getObjectClass();
                if (objectClass != null && objectClass == expectClass) {
                    provider.registerIfAbsent(typeHash, objectReader);
                    return objectReader;
                }
            }

            AutoTypeBeforeHandler autoTypeBeforeHandler = context.autoTypeBeforeHandler;
            if (autoTypeBeforeHandler != null) {
                Class<?> objectClass = autoTypeBeforeHandler.apply(typeHash, expectClass, features);
                if (objectClass == null) {
                    objectClass = autoTypeBeforeHandler.apply(getString(), expectClass, features);
                }
                if (objectClass != null) {
                    ObjectReader objectReader = context.getObjectReader(objectClass);
                    if (objectReader != null) {
                        return objectReader;
                    }
                }
            }

            final long features2 = context.features | features;
            if ((features2 & Feature.SupportAutoType.mask) == 0) {
                if ((features2 & Feature.ErrorOnNotSupportAutoType.mask) == 0) {
                    return null;
                }
                autoTypeError();
            }

            autoTypeObjectReader = provider.getObjectReader(typeHash);

            if (autoTypeObjectReader != null) {
                Class objectClass = autoTypeObjectReader.getObjectClass();
                if (objectClass != null) {
                    ClassLoader classLoader = objectClass.getClassLoader();
                    if (classLoader != null) {
                        ClassLoader tcl = Thread.currentThread().getContextClassLoader();
                        if (classLoader != tcl) {
                            autoTypeObjectReader = getObjectReaderContext(autoTypeObjectReader, objectClass, tcl);
                        }
                    }
                }
            }

            if (autoTypeObjectReader == null) {
                autoTypeObjectReader = provider.getObjectReader(getString(), expectClass, features2);
                if (autoTypeObjectReader == null) {
                    autoTypeError();
                }
            }

            this.type = bytes[offset];
        }
        return autoTypeObjectReader;
    }

    final void autoTypeError() {
        throw new JSONException("auotype not support : " + getString());
    }

    private ObjectReader getObjectReaderContext(
            ObjectReader autoTypeObjectReader,
            Class objectClass,
            ClassLoader contextClassLoader
    ) {
        String typeName = getString();
        Class contextClass = TypeUtils.getMapping(typeName);
        if (contextClass == null) {
            try {
                if (contextClassLoader == null) {
                    contextClassLoader = JSON.class.getClassLoader();
                }
                contextClass = contextClassLoader.loadClass(typeName);
            } catch (ClassNotFoundException ignored) {
            }
        }

        if (contextClass != null && !objectClass.equals(contextClass)) {
            autoTypeObjectReader = getObjectReader(contextClass);
        }
        return autoTypeObjectReader;
    }

    @Override
    public int startArray() {
        final byte type = this.type = bytes[offset++];

        if (type == BC_NULL) {
            return -1;
        }

        if (type >= BC_ARRAY_FIX_MIN && type <= BC_ARRAY_FIX_MAX) {
            ch = (char) -type;
            return type - BC_ARRAY_FIX_MIN;
        }

        if (type == BC_BINARY) {
            return readInt32Value();
        }

        if (type != BC_ARRAY) {
            throw new JSONException("array not support input " + error(type));
        }

        return readInt32Value();
    }

    public String error(byte type) {
        StringBuilder buf = new StringBuilder();

        buf.append(typeName(type));
        if (isString()) {
            int mark = offset;
            offset--;

            String str = null;
            try {
                str = readString();
            } catch (Throwable ignored) {
            }
            if (str != null) {
                buf.append(' ');
                buf.append(str);
            }

            offset = mark;
        }

        buf.append(", offset ");
        buf.append(offset);
        buf.append('/');
        buf.append(bytes.length);

        return buf.toString();
    }

    @Override
    public void next() {
        offset++;
    }

    @Override
    public long readFieldNameHashCode() {
        byte[] bytes = this.bytes;
        byte strtype = this.strtype = bytes[offset++];
        boolean typeSymbol = strtype == BC_SYMBOL;

        if (typeSymbol) {
            strtype = this.strtype = bytes[offset];
            if (strtype >= BC_INT32_NUM_MIN && strtype <= BC_INT32) {
                int symbol;
                if (strtype <= BC_INT32_NUM_MAX) {
                    offset++;
                    symbol = strtype;
                } else {
                    symbol = readInt32Value();
                }

                if (symbol < 0) {
                    return symbolTable.getHashCode(-symbol);
                }

                if (symbol == 0) {
                    this.strtype = symbol0StrType;
                    this.strlen = symbol0Length;
                    this.strBegin = symbol0Begin;
                    if (symbol0Hash == 0) {
                        symbol0Hash = getNameHashCode();
                    }
                    return symbol0Hash;
                }

                int index = symbol * 2;
                long strInfo = symbols[index + 1];
                this.strtype = (byte) strInfo;
                this.strlen = ((int) strInfo) >> 8;
                this.strBegin = (int) (strInfo >> 32);
                long nameHashCode = symbols[index];
                if (nameHashCode == 0) {
                    nameHashCode = getNameHashCode();
                    symbols[index] = nameHashCode;
                }
                return nameHashCode;
            }
            offset++;
        }

        final int strlen;
        if (strtype >= BC_STR_ASCII_FIX_MIN && strtype <= BC_STR_ASCII_FIX_MAX) {
            strlen = strtype - BC_STR_ASCII_FIX_MIN;
        } else if (strtype == BC_STR_ASCII || strtype == BC_STR_UTF8) {
            strlen = readLength();
        } else {
            throw readFieldNameHashCodeError();
        }
        this.strlen = strlen;
        strBegin = offset;
        long hashCode;
        if (strlen < 0) {
            hashCode = symbolTable.getHashCode(-strlen);
        } else {
            long nameValue = 0;
            if (strlen <= 8 && offset + strlen <= bytes.length) {
                int offset = this.offset;
                switch (strlen) {
                    case 1:
                        nameValue = bytes[offset];
                        break;
                    case 2:
                        nameValue = UNSAFE.getShort(bytes, BASE + offset) & 0xFFFFL;
                        break;
                    case 3:
                        nameValue = (bytes[offset + 2] << 16)
                                + (UNSAFE.getShort(bytes, BASE + offset) & 0xFFFFL);
                        break;
                    case 4:
                        nameValue = UNSAFE.getInt(bytes, BASE + offset);
                        break;
                    case 5:
                        nameValue = (((long) bytes[offset + 4]) << 32)
                                + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                        break;
                    case 6:
                        nameValue = ((long) UNSAFE.getShort(bytes, BASE + offset + 4) << 32)
                                + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                        break;
                    case 7:
                        nameValue = (((long) bytes[offset + 6]) << 48)
                                + (((long) bytes[offset + 5] & 0xFFL) << 40)
                                + (((long) bytes[offset + 4] & 0xFFL) << 32)
                                + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                        break;
                    default:
                        nameValue = UNSAFE.getLong(bytes, BASE + offset);
                        break;
                }
            }

            if (nameValue != 0) {
                offset += strlen;
                hashCode = nameValue;
            } else {
                hashCode = Fnv.MAGIC_HASH_CODE;
                for (int i = 0; i < strlen; ++i) {
                    hashCode ^= bytes[offset++];
                    hashCode *= Fnv.MAGIC_PRIME;
                }
            }
        }

        if (typeSymbol) {
            int symbol;
            if ((symbol = bytes[offset]) >= BC_INT32_NUM_MIN && symbol <= BC_INT32_NUM_MAX) {
                offset++;
            } else {
                symbol = readInt32Value();
            }

            if (symbol == 0) {
                symbol0Begin = strBegin;
                symbol0Length = strlen;
                symbol0StrType = strtype;
                symbol0Hash = hashCode;
            } else {
                int symbolIndex = symbol << 1;
                int minCapacity = symbolIndex + 2;
                if (symbols == null) {
                    symbols = new long[Math.max(minCapacity, 32)];
                } else if (symbols.length < minCapacity) {
                    symbols = Arrays.copyOf(symbols, minCapacity + 16);
                }

                symbols[symbolIndex] = hashCode;
                symbols[symbolIndex + 1] = ((long) strBegin << 32) + ((long) strlen << 8) + strtype;
            }
        }

        return hashCode;
    }

    JSONException readFieldNameHashCodeError() {
        StringBuilder message = new StringBuilder()
                .append("fieldName not support input type ")
                .append(typeName(strtype));
        if (strtype == BC_REFERENCE) {
            message.append(" ")
                    .append(readString());
        }

        message.append(", offset ")
                .append(offset);
        return new JSONException(message.toString());
    }

    @Override
    public boolean isInt() {
        int type = bytes[offset];
        return (type >= BC_BIGINT_LONG && type <= BC_INT32)
                || type == BC_TIMESTAMP_SECONDS
                || type == BC_TIMESTAMP_MINUTES
                || type == BC_TIMESTAMP_MILLIS;
    }

    @Override
    public boolean isNull() {
        return bytes[offset] == BC_NULL;
    }

    @Override
    public Date readNullOrNewDate() {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfNull() {
        if (bytes[offset] == BC_NULL) {
            offset++;
            return true;
        }
        return false;
    }

    @Override
    public void readNull() {
        type = bytes[offset++];
        if (type != BC_NULL) {
            throw new JSONException("null not match, " + type);
        }
    }

    @Override
    public boolean readIfNull() {
        if (bytes[offset] == BC_NULL) {
            offset++;
            return true;
        }
        return false;
    }

    @Override
    public long readTypeHashCode() {
        final byte[] bytes = this.bytes;
        int offset = this.offset;
        byte strtype = this.strtype = bytes[offset];
        int type, typelen;
        long hashCode;
        // strlen > 8
        if (strtype == BC_STR_ASCII && (type = bytes[++offset]) > 8 && type <= BC_INT32_BYTE_MAX) {
            if (type <= BC_INT32_NUM_MAX) {
                offset++;
                typelen = type;
            } else {
                typelen = ((type - BC_INT32_BYTE_ZERO) << 8) + (bytes[offset + 1] & 0xFF);
                offset += 2;
            }

            int strBegin = offset;
            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; i < typelen; ++i) {
                hashCode ^= bytes[offset++];
                hashCode *= Fnv.MAGIC_PRIME;
            }

            int symbol;
            if ((symbol = bytes[offset]) >= 0 && symbol <= BC_INT32_NUM_MAX) {
                offset++;

                if (symbol == 0) {
                    symbol0Begin = strBegin;
                    symbol0Length = typelen;
                    symbol0StrType = strtype;
                    symbol0Hash = hashCode;
                } else {
                    int minCapacity = symbol * 2 + 2;

                    if (symbols == null) {
                        symbols = new long[Math.max(minCapacity, 32)];
                    } else if (symbols.length < minCapacity) {
                        symbols = Arrays.copyOf(symbols, minCapacity + 16);
                    }

                    symbols[symbol * 2 + 1] = ((long) strBegin << 32) + ((long) typelen << 8) + strtype;
                }

                this.strBegin = strBegin;
                this.strlen = typelen;
                this.offset = offset;

                return hashCode;
            }
        }
        return readTypeHashCode0();
    }

    public long readTypeHashCode0() {
        final byte[] bytes = this.bytes;
        byte strtype = this.strtype = bytes[offset];
        if (strtype == BC_SYMBOL) {
            offset++;
            strtype = this.strtype = bytes[offset];
            if (strtype >= BC_INT32_NUM_MIN && strtype <= BC_INT32) {
                int symbol;
                if (strtype <= BC_INT32_NUM_MAX) {
                    offset++;
                    symbol = strtype;
                } else {
                    symbol = readInt32Value();
                }

                if (symbol < 0) {
                    return symbolTable.getHashCode(-symbol);
                }

                if (symbol == 0) {
                    this.strtype = symbol0StrType;
                    strlen = symbol0Length;
                    strBegin = symbol0Begin;
                    if (symbol0Hash == 0) {
                        symbol0Hash = getNameHashCode();
                    }
                    return symbol0Hash;
                }

                int index = symbol * 2;
                long strInfo = symbols[index + 1];
                this.strtype = (byte) strInfo;
                strlen = ((int) strInfo) >> 8;
                strBegin = (int) (strInfo >> 32);
                long nameHashCode = symbols[index];
                if (nameHashCode == 0) {
                    nameHashCode = getNameHashCode();
                    symbols[index] = nameHashCode;
                }
                return nameHashCode;
            }
        }

        if (strtype >= BC_INT32_NUM_MIN && strtype <= BC_INT32) {
            int typeIndex;
            if (strtype <= BC_INT32_NUM_MAX) {
                offset++;
                typeIndex = strtype;
            } else if (strtype <= BC_INT32_BYTE_MAX) {
                offset++;
                typeIndex = ((strtype - BC_INT32_BYTE_ZERO) << 8)
                        + (bytes[offset++] & 0xFF);
            } else {
                typeIndex = readInt32Value();
            }

            long refTypeHash;
            if (typeIndex == 0) {
                this.strtype = symbol0StrType;
                strlen = symbol0Length;
                strBegin = symbol0Begin;
                if (symbol0Hash == 0) {
                    symbol0Hash = Fnv.hashCode64(getString());
                }
                refTypeHash = symbol0Hash;
            } else if (typeIndex < 0) {
                strlen = strtype;
                refTypeHash = symbolTable.getHashCode(-typeIndex);
            } else {
                refTypeHash = symbols[typeIndex * 2];
                if (refTypeHash == 0) {
                    long strInfo = symbols[typeIndex * 2 + 1];
                    this.strtype = (byte) strInfo;
                    strlen = ((int) strInfo) >> 8;
                    strBegin = (int) (strInfo >> 32);
                    refTypeHash = Fnv.hashCode64(getString());
                }
            }

            if (refTypeHash == -1) {
                throw typeRefNotFound(typeIndex);
            }

            return refTypeHash;
        }

        offset++;
        strBegin = offset;
        if (strtype >= BC_STR_ASCII_FIX_MIN && strtype <= BC_STR_ASCII_FIX_MAX) {
            strlen = strtype - BC_STR_ASCII_FIX_MIN;
        } else if (strtype == BC_STR_ASCII
                || strtype == BC_STR_UTF8
                || strtype == BC_STR_UTF16
                || strtype == BC_STR_UTF16LE
                || strtype == BC_STR_UTF16BE
        ) {
            byte type = bytes[offset];
            if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                offset++;
                strlen = type;
            } else if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                offset++;
                strlen = ((type - BC_INT32_BYTE_ZERO) << 8)
                        + (bytes[offset++] & 0xFF);
            } else {
                strlen = readLength();
            }
            strBegin = offset;
        } else {
            throw readStringError();
        }

        long hashCode;
        if (strlen < 0) {
            hashCode = symbolTable.getHashCode(-strlen);
        } else if (strtype == BC_STR_UTF8) {
            hashCode = Fnv.MAGIC_HASH_CODE;
            int end = offset + strlen;
            while (offset < end) {
                int c = bytes[offset];

                if (c >= 0) {
                    offset++;
                } else {
                    c &= 0xFF;
                    switch (c >> 4) {
                        case 12:
                        case 13: {
                            /* 110x xxxx   10xx xxxx*/
                            c = char2_utf8(c, bytes[offset + 1], offset);
                            offset += 2;
                            break;
                        }
                        case 14: {
                            c = char2_utf8(c, bytes[offset + 1], bytes[offset + 2], offset);
                            offset += 3;
                            break;
                        }
                        default:
                            /* 10xx xxxx,  1111 xxxx */
                            throw new JSONException("malformed input around byte " + offset);
                    }
                }

                hashCode ^= c;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        } else if (strtype == BC_STR_UTF16 || strtype == BC_STR_UTF16BE) {
            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; i < strlen; i += 2) {
                byte c0 = bytes[offset + i];
                byte c1 = bytes[offset + i + 1];
                char ch = (char) ((c1 & 0xff) | ((c0 & 0xff) << 8));
                hashCode ^= ch;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        } else if (strtype == BC_STR_UTF16LE) {
            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; i < strlen; i += 2) {
                byte c0 = bytes[offset + i];
                byte c1 = bytes[offset + i + 1];
                char ch = (char) ((c0 & 0xff) | ((c1 & 0xff) << 8));
                hashCode ^= ch;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        } else {
            long nameValue = 0;
            if (strlen <= 8) {
                for (int i = 0, start = offset; i < strlen; offset++, i++) {
                    byte c = bytes[offset];
                    if (c < 0 || (c == 0 && bytes[start] == 0)) {
                        nameValue = 0;
                        offset = start;
                        break;
                    }

                    switch (i) {
                        case 0:
                            nameValue = c;
                            break;
                        case 1:
                            nameValue = ((c) << 8) + (nameValue & 0xFFL);
                            break;
                        case 2:
                            nameValue = ((c) << 16) + (nameValue & 0xFFFFL);
                            break;
                        case 3:
                            nameValue = ((c) << 24) + (nameValue & 0xFFFFFFL);
                            break;
                        case 4:
                            nameValue = (((long) c) << 32) + (nameValue & 0xFFFFFFFFL);
                            break;
                        case 5:
                            nameValue = (((long) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                            break;
                        case 6:
                            nameValue = (((long) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                            break;
                        case 7:
                            nameValue = (((long) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                            break;
                        default:
                            break;
                    }
                }
            }

            if (nameValue != 0) {
                hashCode = nameValue;
            } else {
                hashCode = Fnv.MAGIC_HASH_CODE;
                for (int i = 0; i < strlen; ++i) {
                    byte c = bytes[offset++];
                    hashCode ^= c;
                    hashCode *= Fnv.MAGIC_PRIME;
                }
            }
        }

        int symbol;
        if ((type = bytes[offset]) >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
            symbol = type;
            offset++;
        } else {
            symbol = readInt32Value();
        }

        if (symbol == 0) {
            symbol0Begin = strBegin;
            symbol0Length = strlen;
            symbol0StrType = strtype;
            symbol0Hash = hashCode;
        } else {
            int minCapacity = symbol * 2 + 2;

            if (symbols == null) {
                symbols = new long[Math.max(minCapacity, 32)];
            } else if (symbols.length < minCapacity) {
                symbols = Arrays.copyOf(symbols, minCapacity + 16);
            }

            long strInfo = ((long) strBegin << 32) + ((long) strlen << 8) + strtype;
            symbols[symbol * 2 + 1] = strInfo;
        }

        return hashCode;
    }

    @Override
    public long readValueHashCode() {
        final byte[] bytes = this.bytes;
        final byte strtype = this.strtype = bytes[offset++];
        strBegin = offset;
        if (strtype >= BC_STR_ASCII_FIX_MIN && strtype <= BC_STR_ASCII_FIX_MAX) {
            strlen = strtype - BC_STR_ASCII_FIX_MIN;
        } else if (strtype == BC_STR_ASCII
                || strtype == BC_STR_UTF8
                || strtype == BC_STR_UTF16
                || strtype == BC_STR_UTF16LE
                || strtype == BC_STR_UTF16BE
        ) {
            strlen = readLength();
            strBegin = offset;
        } else if (strtype == BC_SYMBOL) {
            strlen = readLength();
            strBegin = offset;
        } else {
            throw readStringError();
        }

        long hashCode;
        if (strlen < 0) {
            hashCode = symbolTable.getHashCode(-strlen);
        } else if (strtype == BC_STR_UTF8) {
            hashCode = Fnv.MAGIC_HASH_CODE;
            int end = offset + strlen;
            while (offset < end) {
                int c = bytes[offset];

                if (c >= 0) {
                    offset++;
                } else {
                    c &= 0xFF;
                    switch (c >> 4) {
                        case 12:
                        case 13: {
                            /* 110x xxxx   10xx xxxx*/
                            c = char2_utf8(c, bytes[offset + 1], offset);
                            offset += 2;
                            break;
                        }
                        case 14: {
                            c = char2_utf8(c, bytes[offset + 1], bytes[offset + 2], offset);
                            offset += 3;
                            break;
                        }
                        default:
                            /* 10xx xxxx,  1111 xxxx */
                            throw new JSONException("malformed input around byte " + offset);
                    }
                }

                hashCode ^= c;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        } else if (strtype == BC_STR_UTF16) {
            final int offset = this.offset;
            hashCode = Fnv.MAGIC_HASH_CODE;
            if (bytes[offset] == (byte) 0xFE
                    && bytes[offset + 1] == (byte) 0xFF
            ) {
                if (strlen <= 16) {
                    long nameValue = 0;
                    for (int i = 2; i < strlen; i += 2) {
                        byte c0 = bytes[offset + i];
                        byte c1 = bytes[offset + i + 1];
                        char ch = (char) ((c1 & 0xff) | ((c0 & 0xff) << 8));

                        if (ch > 0x7F || (i == 0 && ch == 0)) {
                            nameValue = 0;
                            break;
                        }

                        byte c = (byte) ch;
                        switch ((i - 2) >> 1) {
                            case 0:
                                nameValue = c;
                                break;
                            case 1:
                                nameValue = ((c) << 8) + (nameValue & 0xFFL);
                                break;
                            case 2:
                                nameValue = ((c) << 16) + (nameValue & 0xFFFFL);
                                break;
                            case 3:
                                nameValue = ((c) << 24) + (nameValue & 0xFFFFFFL);
                                break;
                            case 4:
                                nameValue = (((long) c) << 32) + (nameValue & 0xFFFFFFFFL);
                                break;
                            case 5:
                                nameValue = (((long) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                                break;
                            case 6:
                                nameValue = (((long) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                                break;
                            case 7:
                                nameValue = (((long) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                                break;
                            default:
                                break;
                        }
                    }

                    if (nameValue != 0) {
                        return nameValue;
                    }
                }

                for (int i = 2; i < strlen; i += 2) {
                    byte c0 = bytes[offset + i];
                    byte c1 = bytes[offset + i + 1];
                    char ch = (char) ((c1 & 0xff) | ((c0 & 0xff) << 8));
                    hashCode ^= ch;
                    hashCode *= Fnv.MAGIC_PRIME;
                }
            } else if (bytes[offset] == (byte) 0xFF
                    && bytes[offset + 1] == (byte) 0xFE
            ) {
                for (int i = 2; i < strlen; i += 2) {
                    byte c1 = bytes[offset + i];
                    byte c0 = bytes[offset + i + 1];
                    char ch = (char) ((c1 & 0xff) | ((c0 & 0xff) << 8));
                    hashCode ^= ch;
                    hashCode *= Fnv.MAGIC_PRIME;
                }
            } else {
                for (int i = 0; i < strlen; i += 2) {
                    byte c0 = bytes[offset + i];
                    byte c1 = bytes[offset + i + 1];
                    char ch = (char) ((c0 & 0xff) | ((c1 & 0xff) << 8));
                    hashCode ^= ch;
                    hashCode *= Fnv.MAGIC_PRIME;
                }
            }
        } else if (strtype == BC_STR_UTF16BE) {
            final int offset = this.offset;
            if (strlen <= 16) {
                long nameValue = 0;
                for (int i = 0; i < strlen; i += 2) {
                    byte c0 = bytes[offset + i];
                    byte c1 = bytes[offset + i + 1];
                    char ch = (char) ((c1 & 0xff) | ((c0 & 0xff) << 8));

                    if (ch > 0x7F || (i == 0 && ch == 0)) {
                        nameValue = 0;
                        break;
                    }

                    byte c = (byte) ch;
                    switch (i >> 1) {
                        case 0:
                            nameValue = c;
                            break;
                        case 1:
                            nameValue = ((c) << 8) + (nameValue & 0xFFL);
                            break;
                        case 2:
                            nameValue = ((c) << 16) + (nameValue & 0xFFFFL);
                            break;
                        case 3:
                            nameValue = ((c) << 24) + (nameValue & 0xFFFFFFL);
                            break;
                        case 4:
                            nameValue = (((long) c) << 32) + (nameValue & 0xFFFFFFFFL);
                            break;
                        case 5:
                            nameValue = (((long) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                            break;
                        case 6:
                            nameValue = (((long) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                            break;
                        case 7:
                            nameValue = (((long) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                            break;
                        default:
                            break;
                    }
                }

                if (nameValue != 0) {
                    return nameValue;
                }
            }

            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; i < strlen; i += 2) {
                byte c0 = bytes[offset + i];
                byte c1 = bytes[offset + i + 1];
                char ch = (char) ((c1 & 0xff) | ((c0 & 0xff) << 8));
                hashCode ^= ch;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        } else if (strtype == BC_STR_UTF16LE) {
            final int offset = this.offset;
            if (strlen <= 16) {
                long nameValue = 0;
                for (int i = 0; i < strlen; i += 2) {
                    byte c0 = bytes[offset + i];
                    byte c1 = bytes[offset + i + 1];
                    char ch = (char) ((c0 & 0xff) | ((c1 & 0xff) << 8));

                    if (ch > 0x7F || (i == 0 && ch == 0)) {
                        nameValue = 0;
                        break;
                    }

                    byte c = (byte) ch;
                    switch (i >> 1) {
                        case 0:
                            nameValue = c;
                            break;
                        case 1:
                            nameValue = ((c) << 8) + (nameValue & 0xFFL);
                            break;
                        case 2:
                            nameValue = ((c) << 16) + (nameValue & 0xFFFFL);
                            break;
                        case 3:
                            nameValue = ((c) << 24) + (nameValue & 0xFFFFFFL);
                            break;
                        case 4:
                            nameValue = (((long) c) << 32) + (nameValue & 0xFFFFFFFFL);
                            break;
                        case 5:
                            nameValue = (((long) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                            break;
                        case 6:
                            nameValue = (((long) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                            break;
                        case 7:
                            nameValue = (((long) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                            break;
                        default:
                            break;
                    }
                }

                if (nameValue != 0) {
                    return nameValue;
                }
            }

            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; i < strlen; i += 2) {
                byte c0 = bytes[offset + i];
                byte c1 = bytes[offset + i + 1];
                char ch = (char) ((c0 & 0xff) | ((c1 & 0xff) << 8));
                hashCode ^= ch;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        } else {
            if (strlen <= 8) {
                long nameValue = 0;
                for (int i = 0, start = offset; i < strlen; offset++, i++) {
                    byte c = bytes[offset];
                    if (c < 0 || (c == 0 && bytes[start] == 0)) {
                        nameValue = 0;
                        offset = start;
                        break;
                    }

                    switch (i) {
                        case 0:
                            nameValue = c;
                            break;
                        case 1:
                            nameValue = ((c) << 8) + (nameValue & 0xFFL);
                            break;
                        case 2:
                            nameValue = ((c) << 16) + (nameValue & 0xFFFFL);
                            break;
                        case 3:
                            nameValue = ((c) << 24) + (nameValue & 0xFFFFFFL);
                            break;
                        case 4:
                            nameValue = (((long) c) << 32) + (nameValue & 0xFFFFFFFFL);
                            break;
                        case 5:
                            nameValue = (((long) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                            break;
                        case 6:
                            nameValue = (((long) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                            break;
                        case 7:
                            nameValue = (((long) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                            break;
                        default:
                            break;
                    }
                }

                if (nameValue != 0) {
                    return nameValue;
                }
            }

            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; i < strlen; ++i) {
                byte c = bytes[offset++];
                hashCode ^= c;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        }

        return hashCode;
    }

    protected final long getNameHashCode() {
        int offset = strBegin;

        {
            long nameValue = 0;
            for (int i = 0; i < strlen; offset++) {
                byte c = bytes[offset];
                if (c < 0 || i >= 8 || (i == 0 && bytes[strBegin] == 0)) {
                    offset = strBegin;
                    nameValue = 0;
                    break;
                }

                switch (i) {
                    case 0:
                        nameValue = c;
                        break;
                    case 1:
                        nameValue = ((c) << 8) + (nameValue & 0xFFL);
                        break;
                    case 2:
                        nameValue = ((c) << 16) + (nameValue & 0xFFFFL);
                        break;
                    case 3:
                        nameValue = ((c) << 24) + (nameValue & 0xFFFFFFL);
                        break;
                    case 4:
                        nameValue = (((long) c) << 32) + (nameValue & 0xFFFFFFFFL);
                        break;
                    case 5:
                        nameValue = (((long) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                        break;
                    case 6:
                        nameValue = (((long) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                        break;
                    case 7:
                        nameValue = (((long) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                        break;
                    default:
                        break;
                }
                i++;
            }

            if (nameValue != 0) {
                return nameValue;
            }
        }

        long hashCode = Fnv.MAGIC_HASH_CODE;
        for (int i = 0; i < strlen; ++i) {
            byte c = bytes[offset++];
            hashCode ^= c;
            hashCode *= Fnv.MAGIC_PRIME;
        }
        return hashCode;
    }

    @Override
    public long getNameHashCodeLCase() {
        int offset = strBegin;

        {
            long nameValue = 0;
            for (int i = 0; i < strlen; offset++) {
                byte c = bytes[offset];
                if (c < 0 || i >= 8 || (i == 0 && bytes[strBegin] == 0)) {
                    offset = strBegin;
                    nameValue = 0;
                    break;
                }

                if (c == '_' || c == '-' || c == ' ') {
                    byte c1 = bytes[offset + 1];
                    if (c1 != c) {
                        continue;
                    }
                }

                if (c >= 'A' && c <= 'Z') {
                    c += 32;
                }

                switch (i) {
                    case 0:
                        nameValue = c;
                        break;
                    case 1:
                        nameValue = ((c) << 8) + (nameValue & 0xFFL);
                        break;
                    case 2:
                        nameValue = ((c) << 16) + (nameValue & 0xFFFFL);
                        break;
                    case 3:
                        nameValue = ((c) << 24) + (nameValue & 0xFFFFFFL);
                        break;
                    case 4:
                        nameValue = (((long) c) << 32) + (nameValue & 0xFFFFFFFFL);
                        break;
                    case 5:
                        nameValue = (((long) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                        break;
                    case 6:
                        nameValue = (((long) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                        break;
                    case 7:
                        nameValue = (((long) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                        break;
                    default:
                        break;
                }
                i++;
            }

            if (nameValue != 0) {
                return nameValue;
            }
        }

        long hashCode = Fnv.MAGIC_HASH_CODE;
        for (int i = 0; i < strlen; ++i) {
            byte c = bytes[offset++];
            if (c >= 'A' && c <= 'Z') {
                c = (byte) (c + 32);
            }

            if (c == '_' || c == '-' || c == ' ') {
                continue;
            }

            hashCode ^= c;
            hashCode *= Fnv.MAGIC_PRIME;
        }
        return hashCode;
    }

    @Override
    public void skipValue() {
        byte type = bytes[offset++];
        switch (type) {
            case BC_NULL:
            case BC_ARRAY_FIX_0:
            case BC_STR_ASCII_FIX_0:
            case BC_FALSE:
            case BC_TRUE:
                return;
            case BC_INT8:
                offset++;
                return;
            case BC_INT16:
                offset += 2;
                return;
            case BC_INT32:
            case BC_TIMESTAMP_SECONDS:
            case BC_TIMESTAMP_MINUTES:
            case BC_FLOAT:
            case BC_INT64_INT:
                offset += 4;
                return;
            case BC_FLOAT_INT:
                readInt32Value(); // skip
                return;
            case BC_INT64:
            case BC_TIMESTAMP_MILLIS:
            case BC_DOUBLE:
                offset += 8;
                return;
            case BC_DOUBLE_LONG:
                readInt64Value();
                return;
            case BC_DECIMAL:
                // TODO skip big decimal
                readInt32Value();
                readBigInteger();
                return;
            case BC_DECIMAL_LONG:
                readInt64Value();
                return;
            case BC_LOCAL_TIME:
                offset += 3;
                readInt32Value(); // skip
                return;
            case BC_LOCAL_DATETIME:
                offset += 7;
                readInt32Value(); // skip
                return;
            case BC_TIMESTAMP_WITH_TIMEZONE:
                offset += 7;
                readInt32Value(); // nano
                readString(); // skip
                return;
            case BC_BINARY:
                int byteslen = readInt32Value();
                offset += byteslen;
                return;
            case BC_STR_ASCII:
            case BC_STR_UTF8:
            case BC_STR_UTF16:
            case BC_STR_UTF16LE:
            case BC_STR_UTF16BE:
                int strlen = readInt32Value();
                offset += strlen;
                return;
            case BC_TYPED_ANY: {
                readTypeHashCode();
                skipValue();
                return;
            }
            case BC_OBJECT: {
                while (true) {
                    byte b = bytes[offset];
                    if (b == BC_OBJECT_END) {
                        offset++;
                        break;
                    }

                    int size = FIXED_TYPE_SIZE[b & 0xFF];
                    if (size > 0) {
                        offset += size;
                    } else if (size == -1) {
                        offset++;
                        int len = readInt32Value();
                        offset += len;
                    } else {
                        skipName();
                    }

                    b = bytes[offset];
                    size = FIXED_TYPE_SIZE[b & 0xFF];
                    if (size > 0) {
                        offset += size;
                    } else if (size == -1) {
                        offset++;
                        int len = readInt32Value();
                        offset += len;
                    } else {
                        skipValue();
                    }
                }
                return;
            }
            case BC_REFERENCE: {
                if (isString()) {
                    skipName();
                    return;
                }
                throw new JSONException("skip not support type " + typeName(type));
            }
            default:
                if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                    return;
                }

                if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
                    return;
                }

                if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                    offset++;
                    return;
                }

                if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
                    offset += 2;
                    return;
                }

                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    offset += (type - BC_STR_ASCII_FIX_MIN);
                    return;
                }

                if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
                    offset++;
                    return;
                }

                if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
                    offset += 2;
                    return;
                }

                if (type >= BC_ARRAY_FIX_MIN && type <= BC_ARRAY) {
                    int itemCnt;
                    if (type == BC_ARRAY) {
                        itemCnt = readInt32Value();
                    } else {
                        itemCnt = type - BC_ARRAY_FIX_MIN;
                    }
                    for (int i = 0; i < itemCnt; ++i) {
                        int size = FIXED_TYPE_SIZE[bytes[offset] & 0xFF];
                        if (size > 0) {
                            offset += size;
                        } else if (size == -1) {
                            offset++;
                            int len = readInt32Value();
                            offset += len;
                        } else {
                            skipValue();
                        }
                    }
                    return;
                }

                throw notSupportType(type);
        }
    }

    @Override
    public boolean skipName() {
        strtype = bytes[offset++];
        if (strtype >= BC_STR_ASCII_FIX_MIN && strtype <= BC_STR_ASCII_FIX_MAX) {
            offset += (strtype - BC_STR_ASCII_FIX_MIN);
            return true;
        }

        if (strtype == BC_STR_ASCII
                || strtype == BC_STR_UTF8
                || strtype == BC_STR_UTF16
                || strtype == BC_STR_UTF16LE
                || strtype == BC_STR_UTF16BE
        ) {
            strlen = readLength();
            offset += strlen;
            return true;
        }

        if (strtype == BC_SYMBOL) {
            int type = bytes[offset];
            if (type >= BC_INT32_NUM_MIN && type <= BC_INT32) {
                readInt32Value();
                return true;
            }

            readString();
            readInt32Value();
            return true;
        }

        throw notSupportType(strtype);
    }

    private static JSONException notSupportType(byte type) {
        return new JSONException("name not support input : " + typeName(type));
    }

    private JSONException notSupportString() {
        throw new JSONException("readString not support type " + typeName(strtype) + ", offset " + offset + "/" + bytes.length);
    }

    private JSONException readInt32ValueError(byte type) {
        throw new JSONException("readInt32Value not support " + typeName(type) + ", offset " + offset + "/" + bytes.length);
    }

    private JSONException readInt64ValueError(byte type) {
        throw new JSONException("readInt64Value not support " + typeName(type) + ", offset " + offset + "/" + bytes.length);
    }

    private JSONException readStringError() {
        throw new JSONException("string value not support input " + typeName(type) + " offset " + offset + "/" + bytes.length);
    }

    static JSONException typeRefNotFound(int typeIndex) {
        throw new JSONException("type ref not found : " + typeIndex);
    }

    @Override
    public String readFieldName() {
        final byte[] bytes = this.bytes;
        strtype = bytes[offset];
        if (strtype == BC_NULL) {
            offset++;
            return null;
        }
        offset++;

        boolean typeSymbol = strtype == BC_SYMBOL;
        if (typeSymbol) {
            strtype = bytes[offset];
            if (strtype >= BC_INT32_NUM_MIN && strtype <= BC_INT32) {
                int symbol = readInt32Value();
                if (symbol < 0) {
                    return symbolTable.getName(-symbol);
                }

                if (symbol == 0) {
                    strtype = symbol0StrType;
                    strlen = symbol0Length;
                    strBegin = symbol0Begin;
                    return getString();
                }

                int index = symbol * 2 + 1;
                long strInfo = symbols[index];
                strtype = (byte) strInfo;
                strlen = ((int) strInfo) >> 8;
                strBegin = (int) (strInfo >> 32);
                return getString();
            }
            offset++;
        }

        strBegin = offset;
        Charset charset;
        String str = null;
        if (strtype >= BC_STR_ASCII_FIX_MIN && strtype <= BC_STR_ASCII) {
            long nameValue0 = -1, nameValue1 = -1;

            if (strtype == BC_STR_ASCII) {
                strlen = readLength();
                strBegin = offset;
            } else {
                strlen = strtype - BC_STR_ASCII_FIX_MIN;

                if (JDKUtils.BIG_ENDIAN) {
                    switch (strlen) {
                        case 1:
                            nameValue0 = (bytes[offset] & 0xFFL);
                            break;
                        case 2:
                            nameValue0
                                    = ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 3:
                            nameValue0
                                    = (bytes[offset + 2] << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 4:
                            nameValue0 = UNSAFE.getInt(bytes, BASE + offset);
                            break;
                        case 5:
                            nameValue0
                                    = (((long) bytes[offset + 4]) << 32)
                                    + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                            break;
                        case 6:
                            nameValue0
                                    = (((long) bytes[offset + 5]) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                            break;
                        case 7:
                            nameValue0
                                    = (((long) bytes[offset + 6]) << 48)
                                    + ((bytes[offset + 5] & 0xFFL) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                            break;
                        case 8:
                            nameValue0 = UNSAFE.getLong(bytes, BASE + offset);
                            break;
                        case 9:
                            nameValue0 = bytes[offset];
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 1);
                            break;
                        case 10:
                            nameValue0 = UNSAFE.getShort(bytes, BASE + offset);
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 2);
                            break;
                        case 11:
                            nameValue0
                                    = (bytes[offset] << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset + 2] & 0xFFL);
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 3);
                            break;
                        case 12:
                            nameValue0 = UNSAFE.getInt(bytes, BASE + offset);
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 4);
                            break;
                        case 13:
                            nameValue0
                                    = (((long) bytes[offset + 4]) << 32)
                                    + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 5);
                            break;
                        case 14:
                            nameValue0
                                    = (((long) bytes[offset + 5]) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 6);
                            break;
                        case 15:
                            nameValue0
                                    = (((long) bytes[offset + 6]) << 48)
                                    + ((bytes[offset + 5] & 0xFFL) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + (UNSAFE.getInt(bytes, BASE + offset) & 0xFFFFFFFFL);
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 7);
                            break;
                        case 16:
                            nameValue0 = UNSAFE.getLong(bytes, BASE + offset);
                            nameValue1 = UNSAFE.getLong(bytes, BASE + offset + 8);
                            break;
                        default:
                            break;
                    }
                } else {
                    switch (strlen) {
                        case 3:
                            nameValue0
                                    = (bytes[offset + 2] << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 4:
                            nameValue0
                                    = (bytes[offset + 3] << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 5:
                            nameValue0
                                    = (((long) bytes[offset + 4]) << 32)
                                    + ((bytes[offset + 3] & 0xFFL) << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 6:
                            nameValue0
                                    = (((long) bytes[offset + 5]) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + ((bytes[offset + 3] & 0xFFL) << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 7:
                            nameValue0
                                    = (((long) bytes[offset + 6]) << 48)
                                    + ((bytes[offset + 5] & 0xFFL) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + ((bytes[offset + 4] & 0xFFL) << 24)
                                    + ((bytes[offset + 3] & 0xFFL) << 16)
                                    + ((bytes[offset + 2] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 8:
                            nameValue0
                                    = (((long) bytes[offset + 7]) << 56)
                                    + ((bytes[offset + 6] & 0xFFL) << 48)
                                    + ((bytes[offset + 5] & 0xFFL) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + ((bytes[offset + 3] & 0xFFL) << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            break;
                        case 9:
                            nameValue0 = bytes[offset];
                            nameValue1
                                    = (((long) bytes[offset + 8]) << 56)
                                    + ((bytes[offset + 7]) << 48)
                                    + ((bytes[offset + 6] & 0xFFL) << 40)
                                    + ((bytes[offset + 5] & 0xFFL) << 32)
                                    + ((bytes[offset + 4] & 0xFFL) << 24)
                                    + ((bytes[offset + 3] & 0xFFL) << 16)
                                    + ((bytes[offset + 2] & 0xFFL) << 8)
                                    + (bytes[offset + 1] & 0xFFL);
                            break;
                        case 10:
                            nameValue0
                                    = (bytes[offset + 1] << 8)
                                    + (bytes[offset] & 0xFFL);
                            nameValue1
                                    = (((long) bytes[offset + 9]) << 56)
                                    + ((bytes[offset + 8] & 0xFFL) << 48)
                                    + ((bytes[offset + 7] & 0xFFL) << 40)
                                    + ((bytes[offset + 6] & 0xFFL) << 32)
                                    + ((bytes[offset + 5] & 0xFFL) << 24)
                                    + ((bytes[offset + 4] & 0xFFL) << 16)
                                    + ((bytes[offset + 3] & 0xFFL) << 8)
                                    + (bytes[offset + 2] & 0xFFL);
                            break;
                        case 11:
                            nameValue0
                                    = (bytes[offset + 2] << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            nameValue1
                                    = (((long) bytes[offset + 10]) << 56)
                                    + ((bytes[offset + 9] & 0xFFL) << 48)
                                    + ((bytes[offset + 8] & 0xFFL) << 40)
                                    + ((bytes[offset + 7] & 0xFFL) << 32)
                                    + ((bytes[offset + 6] & 0xFFL) << 24)
                                    + ((bytes[offset + 5] & 0xFFL) << 16)
                                    + ((bytes[offset + 4] & 0xFFL) << 8)
                                    + (bytes[offset + 3] & 0xFFL);
                            break;
                        case 12:
                            nameValue0
                                    = (bytes[offset + 3] << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            nameValue1
                                    = (((long) bytes[offset + 11]) << 56)
                                    + ((bytes[offset + 10] & 0xFFL) << 48)
                                    + ((bytes[offset + 9] & 0xFFL) << 40)
                                    + ((bytes[offset + 8] & 0xFFL) << 32)
                                    + ((bytes[offset + 7] & 0xFFL) << 24)
                                    + ((bytes[offset + 6] & 0xFFL) << 16)
                                    + ((bytes[offset + 5] & 0xFFL) << 8)
                                    + (bytes[offset + 4] & 0xFFL);
                            break;
                        case 13:
                            nameValue0
                                    = (((long) bytes[offset + 4]) << 32)
                                    + ((bytes[offset + 3] & 0xFFL) << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            nameValue1
                                    = (((long) bytes[offset + 12]) << 56)
                                    + ((bytes[offset + 11] & 0xFFL) << 48)
                                    + ((bytes[offset + 10] & 0xFFL) << 40)
                                    + ((bytes[offset + 9] & 0xFFL) << 32)
                                    + ((bytes[offset + 8] & 0xFFL) << 24)
                                    + ((bytes[offset + 7] & 0xFFL) << 16)
                                    + ((bytes[offset + 6] & 0xFFL) << 8)
                                    + (bytes[offset + 5] & 0xFFL);
                            break;
                        case 14:
                            nameValue0
                                    = (((long) bytes[offset + 5]) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + ((bytes[offset + 3] & 0xFFL) << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            nameValue1
                                    = (((long) bytes[offset + 13]) << 56)
                                    + ((bytes[offset + 12] & 0xFFL) << 48)
                                    + ((bytes[offset + 11] & 0xFFL) << 40)
                                    + ((bytes[offset + 10] & 0xFFL) << 32)
                                    + ((bytes[offset + 9] & 0xFFL) << 24)
                                    + ((bytes[offset + 8] & 0xFFL) << 16)
                                    + ((bytes[offset + 7] & 0xFFL) << 8)
                                    + (bytes[offset + 6] & 0xFFL);
                            break;
                        case 15:
                            nameValue0
                                    = (((long) bytes[offset + 6]) << 48)
                                    + ((bytes[offset + 5] & 0xFFL) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + ((bytes[offset + 3] & 0xFFL) << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            nameValue1
                                    = (((long) bytes[offset + 14]) << 56)
                                    + ((bytes[offset + 13] & 0xFFL) << 48)
                                    + ((bytes[offset + 12] & 0xFFL) << 40)
                                    + ((bytes[offset + 11] & 0xFFL) << 32)
                                    + ((bytes[offset + 10] & 0xFFL) << 24)
                                    + ((bytes[offset + 9] & 0xFFL) << 16)
                                    + ((bytes[offset + 8] & 0xFFL) << 8)
                                    + (bytes[offset + 7] & 0xFFL);
                            break;
                        case 16:
                            nameValue0
                                    = (((long) bytes[offset + 7]) << 56)
                                    + ((bytes[offset + 6]) << 48)
                                    + ((bytes[offset + 5] & 0xFFL) << 40)
                                    + ((bytes[offset + 4] & 0xFFL) << 32)
                                    + ((bytes[offset + 3] & 0xFFL) << 24)
                                    + ((bytes[offset + 2] & 0xFFL) << 16)
                                    + ((bytes[offset + 1] & 0xFFL) << 8)
                                    + (bytes[offset] & 0xFFL);
                            nameValue1
                                    = (((long) bytes[offset + 15]) << 56)
                                    + ((bytes[offset + 14] & 0xFFL) << 48)
                                    + ((bytes[offset + 13] & 0xFFL) << 40)
                                    + ((bytes[offset + 12] & 0xFFL) << 32)
                                    + ((bytes[offset + 11] & 0xFFL) << 24)
                                    + ((bytes[offset + 10] & 0xFFL) << 16)
                                    + ((bytes[offset + 9] & 0xFFL) << 8)
                                    + (bytes[offset + 8] & 0xFFL);
                            break;
                        default:
                            break;
                    }
                }
            }

            if (bytes[offset + strlen - 1] > 0 && nameValue0 != -1) {
                if (nameValue1 != -1) {
                    long nameValue01 = nameValue0 ^ nameValue1;
                    int indexMask = ((int) (nameValue01 ^ (nameValue01 >>> 32))) & (NAME_CACHE2.length - 1);
                    JSONFactory.NameCacheEntry2 entry = NAME_CACHE2[indexMask];
                    if (entry == null) {
                        str = getLatin1String(offset, strlen);
                        NAME_CACHE2[indexMask] = new JSONFactory.NameCacheEntry2(str, nameValue0, nameValue1);
                        offset += strlen;
                    } else if (entry.value0 == nameValue0 && entry.value1 == nameValue1) {
                        offset += strlen;
                        str = entry.name;
                    }
                } else {
                    int indexMask = ((int) (nameValue0 ^ (nameValue0 >>> 32))) & (NAME_CACHE.length - 1);
                    NameCacheEntry entry = NAME_CACHE[indexMask];
                    if (entry == null) {
                        str = getLatin1String(offset, strlen);
                        NAME_CACHE[indexMask] = new NameCacheEntry(str, nameValue0);
                        offset += strlen;
                    } else if (entry.value == nameValue0) {
                        offset += strlen;
                        str = entry.name;
                    }
                }
            }

            charset = StandardCharsets.ISO_8859_1;
        } else if (strtype == BC_STR_UTF8) {
            byte type = bytes[offset];
            if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                strlen = type;
                offset++;
            } else if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                strlen = ((type - BC_INT32_BYTE_ZERO) << 8) + (bytes[offset + 1] & 0xFF);
                offset += 2;
            } else if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
                strlen = getInt3(bytes, offset + 1, type);
                offset += 3;
            } else {
                strlen = readLength();
            }
            strBegin = offset;
            charset = StandardCharsets.UTF_8;
        } else if (strtype == BC_STR_UTF16) {
            strlen = readLength();
            strBegin = offset;
            charset = StandardCharsets.UTF_16;
        } else if (strtype == BC_STR_UTF16LE) {
            strlen = readLength();
            strBegin = offset;
            charset = StandardCharsets.UTF_16LE;
        } else if (strtype == BC_STR_UTF16BE) {
            strlen = readLength();
            strBegin = offset;
            charset = StandardCharsets.UTF_16BE;
        } else if (strtype == BC_STR_GB18030) {
            strlen = readLength();

            if (GB18030 == null) {
                GB18030 = Charset.forName("GB18030");
            }
            charset = GB18030;
        } else {
            charset = StandardCharsets.UTF_8;
        }

        if (strlen < 0) {
            str = symbolTable.getName(-strlen);
        }

        if (str == null) {
            // optimization for android
            if (charset == StandardCharsets.ISO_8859_1 && ANDROID_SDK_INT < 34) {
                char[] charBuf = this.charBuf;
                int strlen = this.strlen;
                if (charBuf == null) {
                    this.charBuf = charBuf = CHARS_UPDATER.getAndSet(cacheItem, null);
                }
                if (charBuf == null || charBuf.length < strlen) {
                    this.charBuf = charBuf = new char[strlen];
                }

                for (int i = 0; i < strlen; i++) {
                    charBuf[i] = (char) (bytes[offset + i] & 0xff);
                }
                str = new String(charBuf, 0, strlen);
            }

            if (str == null) {
                str = new String(bytes, offset, strlen, charset);
            }
            offset += strlen;
        }

        if (typeSymbol) {
            int symbol = readInt32Value();

            if (symbol == 0) {
                symbol0Begin = strBegin;
                symbol0Length = strlen;
                symbol0StrType = strtype;
            } else {
                if (symbols == null) {
                    symbols = new long[symbol * 2 + 2 < 32 ? 32 : symbol * 2 + 2];
                } else if (symbols.length < symbol * 2 + 2) {
                    symbols = Arrays.copyOf(symbols, symbols.length + 16);
                }

                long strInfo = ((long) strBegin << 32) + ((long) strlen << 8) + strtype;
                symbols[symbol * 2 + 1] = strInfo;
            }
        }

        return str;
    }

    @Override
    public String getFieldName() {
        return getString();
    }

    @Override
    public String readString() {
        final byte strtype = this.strtype = bytes[offset++];
        if (strtype == BC_NULL) {
            return null;
        }

        strBegin = offset;
        if (strtype >= BC_STR_ASCII_FIX_MIN && strtype <= BC_STR_ASCII) {
            final int strlen;
            if (strtype == BC_STR_ASCII) {
                byte strType = bytes[offset];
                if (strType >= BC_INT32_NUM_MIN && strType <= BC_INT32_NUM_MAX) {
                    offset++;
                    strlen = strType;
                } else if (strType >= BC_INT32_BYTE_MIN && strType <= BC_INT32_BYTE_MAX) {
                    strlen = ((strType - BC_INT32_BYTE_ZERO) << 8) + (bytes[offset + 1] & 0xFF);
                    offset += 2;
                } else if (strType >= BC_INT32_SHORT_MIN && strType <= BC_INT32_SHORT_MAX) {
                    strlen = getInt3(bytes, offset + 1, strType);
                    offset += 3;
                } else {
                    strlen = readLength();
                }
                strBegin = offset;
            } else {
                strlen = strtype - BC_STR_ASCII_FIX_MIN;
            }

            if (strlen < 0) {
                return symbolTable.getName(-strlen);
            }

            this.strlen = strlen;

            String str;
            if (ANDROID_SDK_INT < 34) {
                char[] charBuf = this.charBuf;
                if (charBuf == null) {
                    this.charBuf = charBuf = CHARS_UPDATER.getAndSet(cacheItem, null);
                }
                if (charBuf == null || charBuf.length < strlen) {
                    this.charBuf = charBuf = new char[strlen];
                }
                for (int i = 0; i < strlen; i++) {
                    charBuf[i] = (char) (bytes[offset + i] & 0xff);
                }
                str = new String(charBuf, 0, strlen);
            } else {
                str = new String(bytes, offset, strlen, StandardCharsets.ISO_8859_1);
            }

            offset += strlen;

            if ((context.features & Feature.TrimString.mask) != 0) {
                str = str.trim();
            }

            if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
                str = null;
            }
            return str;
        }

        return readStringNonAscii();
    }

    private String readStringNonAscii() {
        Charset charset;
        String str = null;
        if (strtype == BC_STR_UTF8) {
            byte type = bytes[offset];
            if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                strlen = type;
                offset++;
            } else if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                strlen = ((type - BC_INT32_BYTE_ZERO) << 8) + (bytes[offset + 1] & 0xFF);
                offset += 2;
            } else {
                strlen = readLength();
            }
            strBegin = offset;
            charset = StandardCharsets.UTF_8;
        } else if (strtype == BC_STR_UTF16) {
            strlen = readLength();
            strBegin = offset;
            charset = StandardCharsets.UTF_16;
        } else if (strtype == BC_STR_UTF16LE) {
            str = readUTF16LE();
            charset = StandardCharsets.UTF_16LE;
        } else if (strtype == BC_STR_UTF16BE) {
            str = readUTF16BE();
            if (str != null) {
                return str;
            }
            charset = StandardCharsets.UTF_16BE;
        } else if (strtype == BC_STR_GB18030) {
            readGB18030();
            charset = GB18030;
        } else {
            return readStringTypeNotMatch();
        }

        if (str != null) {
            if ((context.features & Feature.TrimString.mask) != 0) {
                str = str.trim();
            }
            return str;
        }

        if (strlen < 0) {
            return symbolTable.getName(-strlen);
        }

        str = new String(bytes, offset, strlen, charset);
        offset += strlen;

        if ((context.features & Feature.TrimString.mask) != 0) {
            str = str.trim();
        }

        if (strlen == 0 && (context.features & Feature.EmptyStringAsNull.mask) != 0) {
            str = null;
        }

        return str;
    }

    private String readGB18030() {
        strlen = readLength();
        strBegin = offset;

        if (GB18030 == null) {
            GB18030 = Charset.forName("GB18030");
        }
        return null;
    }

    private String readUTF16BE() {
        strlen = readLength();
        strBegin = offset;
        return null;
    }

    private String readUTF16LE() {
        byte strType = bytes[offset];
        if (strType >= BC_INT32_NUM_MIN && strType <= BC_INT32_NUM_MAX) {
            offset++;
            strlen = strType;
        } else if (strType >= BC_INT32_BYTE_MIN && strType <= BC_INT32_BYTE_MAX) {
            offset++;
            strlen = ((strType - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
        } else {
            strlen = readLength();
        }
        strBegin = offset;

        if (strlen == 0) {
            return "";
        }

        return null;
    }

    private String readStringUTF8() {
        byte strType = bytes[offset];
        if (strType >= BC_INT32_NUM_MIN && strType <= BC_INT32_NUM_MAX) {
            offset++;
            strlen = strType;
        } else if (strType >= BC_INT32_BYTE_MIN && strType <= BC_INT32_BYTE_MAX) {
            offset++;
            strlen = ((strType - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
        } else {
            strlen = readLength();
        }
        strBegin = offset;
        return null;
    }

    private String readStringTypeNotMatch() {
        if (strtype >= BC_INT32_NUM_MIN && strtype <= BC_INT32_NUM_MAX) {
            return Byte.toString(strtype);
        } else if (strtype >= BC_INT32_BYTE_MIN && strtype <= BC_INT32_BYTE_MAX) {
            int intValue = ((strtype - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
            return Integer.toString(intValue);
        } else if (strtype >= BC_INT32_SHORT_MIN && strtype <= BC_INT32_SHORT_MAX) {
            int int3 = getInt3(bytes, offset, strtype);
            offset += 2;
            return Integer.toString(int3);
        } else if (strtype >= BC_INT64_NUM_MIN && strtype <= BC_INT64_NUM_MAX) {
            int intValue = INT64_NUM_LOW_VALUE + (strtype - BC_INT64_NUM_MIN);
            return Integer.toString(intValue);
        } else if (strtype >= BC_INT64_BYTE_MIN && strtype <= BC_INT64_BYTE_MAX) {
            int intValue = ((strtype - BC_INT64_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
            return Integer.toString(intValue);
        } else if (strtype >= BC_INT64_SHORT_MIN && strtype <= BC_INT64_SHORT_MAX) {
            int intValue = ((strtype - BC_INT64_SHORT_ZERO) << 16)
                    + ((bytes[offset++] & 0xFF) << 8)
                    + (bytes[offset++] & 0xFF);
            return Integer.toString(intValue);
        }

        switch (strtype) {
            case BC_NULL:
                return null;
            case BC_DOUBLE_NUM_0:
                return "0.0";
            case BC_DOUBLE_NUM_1:
                return "1.0";
            case BC_INT64_INT:
            case BC_INT32: {
                long int32Value = getInt(bytes, offset);
                offset += 4;
                return Long.toString(int32Value);
            }
            case BC_FLOAT_INT:
                return Float.toString(
                        readInt32Value());
            case BC_FLOAT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                float floatValue = Float.intBitsToFloat(int32Value);
                return Float.toString(floatValue);
            }
            case BC_DOUBLE: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                double doubleValue = Double.longBitsToDouble(
                        BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value)
                );
                return Double.toString(doubleValue);
            }
            case BC_TIMESTAMP_SECONDS: {
                long seconds = getInt(bytes, offset);
                offset += 4;
                long millis = seconds * 1000;
                return DateUtils.toString(millis, false, DEFAULT_ZONE_ID);
            }
            case BC_TIMESTAMP_MINUTES: {
                long minutes = getInt(bytes, offset);
                offset += 4;
                long millis = minutes * 60 * 1000;
                return DateUtils.toString(millis, false, DEFAULT_ZONE_ID);
            }
            case BC_TIMESTAMP_MILLIS: {
                long millis = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return DateUtils.toString(
                        BIG_ENDIAN ? millis : Long.reverseBytes(millis),
                        false,
                        DEFAULT_ZONE_ID
                );
            }
            case BC_INT64:
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                if (!BIG_ENDIAN) {
                    int64Value = Long.reverseBytes(int64Value);
                }
                offset += 8;
                return Long.toString(int64Value);
            case BC_BIGINT: {
                int len = readInt32Value();
                byte[] bytes = new byte[len];
                System.arraycopy(this.bytes, offset, bytes, 0, len);
                offset += len;
                return new BigInteger(bytes).toString();
            }
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                BigDecimal decimal;
                if (scale == 0) {
                    decimal = new BigDecimal(unscaledValue);
                } else {
                    decimal = new BigDecimal(unscaledValue, scale);
                }
                return decimal.toString();
            }
            case BC_TYPED_ANY: {
                offset--;
                Object typedAny = readAny();
                return typedAny == null ? null : JSON.toJSONString(typedAny, JSONWriter.Feature.WriteThrowableClassName);
            }
            case BC_DECIMAL_LONG:
            case BC_BIGINT_LONG: {
                return Long.toString(
                        readInt64Value()
                );
            }
            case BC_DOUBLE_LONG: {
                double doubleValue = readInt64Value();
                return Double.toString(doubleValue);
            }
            default:
                break;
        }
        throw notSupportString();
    }

    public String[] readStringArray() {
        if (nextIfMatch(BC_TYPED_ANY)) {
            long typeHash = readTypeHashCode();
            if (typeHash != ObjectReaderImplStringArray.HASH_TYPE) {
                throw new JSONException(info("not support type " + getString()));
            }
        }

        int entryCnt = startArray();
        if (entryCnt == -1) {
            return null;
        }
        String[] array = new String[entryCnt];
        for (int i = 0; i < entryCnt; i++) {
            array[i] = readString();
        }
        return array;
    }

    @Override
    public char readCharValue() {
        byte type = bytes[offset];
        if (type == BC_CHAR) {
            offset++;
            return (char) readInt32Value();
        } else if (type == BC_STR_ASCII_FIX_0) {
            offset++;
            return '\0';
        } else if (type > BC_STR_ASCII_FIX_0 && type < BC_STR_ASCII_FIX_MAX) {
            offset++;
            return (char) (bytes[offset++] & 0xff);
        }

        String str = readString();
        if (str == null || str.isEmpty()) {
            return '\0';
        }
        return str.charAt(0);
    }

    public long[] readInt64ValueArray() {
        if (nextIfMatch(JSONB.Constants.BC_TYPED_ANY)) {
            long typeHash = readTypeHashCode();
            if (typeHash != ObjectReaderImplInt64ValueArray.HASH_TYPE
                    && typeHash != ObjectReaderImplInt64Array.HASH_TYPE
                    && typeHash != ObjectReaderImplInt32Array.HASH_TYPE
                    && typeHash != ObjectReaderImplInt32ValueArray.HASH_TYPE
            ) {
                throw new JSONException(info("not support " + getString()));
            }
        }

        int entryCnt = startArray();
        if (entryCnt == -1) {
            return null;
        }

        long[] array = new long[entryCnt];
        for (int i = 0; i < entryCnt; i++) {
            array[i] = readInt64Value();
        }
        return array;
    }

    @Override
    public long readInt64Value() {
        wasNull = false;

        final byte[] bytes = this.bytes;
        int offset = this.offset;
        byte type = bytes[offset++];

        long int64Value;
        if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
            int64Value = INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
        } else if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
            int64Value = ((type - BC_INT64_BYTE_ZERO) << 8)
                    + (bytes[offset] & 0xFF);
            offset += 1;
        } else if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
            int64Value = ((type - BC_INT64_SHORT_ZERO) << 16)
                    + ((bytes[offset] & 0xFF) << 8)
                    + (bytes[offset + 1] & 0xFF);
            offset += 2;
        } else if (type == BC_INT64_INT) {
            int64Value = getInt(bytes, offset);
            offset += 4;
        } else if (type == BC_INT64) {
            int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
            if (!BIG_ENDIAN) {
                int64Value = Long.reverseBytes(int64Value);
            }
            offset += 8;
        } else {
            this.offset = offset;
            return readInt64Value0(bytes, type);
        }
        this.offset = offset;
        return int64Value;
    }

    private long readInt64Value0(byte[] bytes, byte type) {
        if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
            return ((type - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
        }

        if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
            return type;
        }

        if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
            int int3 = getInt3(bytes, offset, type);
            offset += 2;
            return int3;
        }

        switch (type) {
            case BC_NULL:
                if ((context.features & Feature.ErrorOnNullForPrimitives.mask) != 0) {
                    throw new JSONException(info("long value not support input null"));
                }
                wasNull = true;
                return 0;
            case BC_FALSE:
            case BC_DOUBLE_NUM_0:
                return 0;
            case BC_TRUE:
            case BC_DOUBLE_NUM_1:
                return 1;
            case BC_INT8:
                return bytes[offset++];
            case BC_INT16:
                int int16Value =
                        ((bytes[offset + 1] & 0xFF) +
                                (bytes[offset] << 8));
                offset += 2;
                return int16Value;
            case BC_INT32: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return int32Value;
            }
            case BC_FLOAT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                float floatValue = Float.intBitsToFloat(int32Value);
                return (long) floatValue;
            }
            case BC_DOUBLE: {
                offset--;
                return (long) readDoubleValue();
            }
            case BC_FLOAT_INT:
                return (long) ((float) readInt32Value());
            case BC_DOUBLE_LONG:
                return (long) ((double) readInt64Value());
            case BC_TIMESTAMP_MINUTES: {
                long minutes = getInt(bytes, offset);
                offset += 4;
                return minutes * 60 * 1000;
            }
            case BC_TIMESTAMP_SECONDS: {
                long seconds = getInt(bytes, offset);
                offset += 4;
                return seconds * 1000;
            }
            case BC_TIMESTAMP_MILLIS:
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value);
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                BigDecimal decimal;
                if (scale == 0) {
                    decimal = new BigDecimal(unscaledValue);
                } else {
                    decimal = new BigDecimal(unscaledValue, scale);
                }
                return decimal.longValue();
            }
            case BC_STR_ASCII: {
                int strlen = readInt32Value();
                String str = getLatin1String(offset, strlen);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF8: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF16LE: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16LE);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            default:
                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    int strlen = type - BC_STR_ASCII_FIX_MIN;
                    String str = getLatin1String(offset, strlen);
                    offset += strlen;
                    if (str.indexOf('.') == -1) {
                        return new BigInteger(str).longValue();
                    } else {
                        return toBigDecimal(str).longValue();
                    }
                }
                break;
        }
        throw new JSONException("readInt64Value not support " + typeName(type) + ", offset " + offset + "/" + bytes.length);
    }

    @Override
    public int readInt32Value() {
        final byte[] bytes = this.bytes;
        int offset = this.offset;
        final byte type = bytes[offset++];

        int int32Value;
        if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
            int32Value = type;
        } else if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
            int32Value = ((type - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset] & 0xFF);
            offset += 1;
        } else if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
            int32Value = getInt3(bytes, offset, type);
            offset += 2;
        } else if (type == BC_INT32) {
            int32Value = getInt(bytes, offset);
            offset += 4;
        } else {
            this.offset = offset;
            return readInt32Value0(bytes, type);
        }
        this.offset = offset;
        return int32Value;
    }

    private int readInt32Value0(byte[] bytes, byte type) {
        if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
            return INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
        }

        if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
            return ((type - BC_INT64_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
        }

        if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
            return ((type - BC_INT64_SHORT_ZERO) << 16)
                    + ((bytes[offset++] & 0xFF) << 8)
                    + (bytes[offset++] & 0xFF);
        }

        switch (type) {
            case BC_NULL:
                if ((context.features & Feature.ErrorOnNullForPrimitives.mask) != 0) {
                    throw new JSONException(info("int value not support input null"));
                }

                wasNull = true;
                return 0;
            case BC_FALSE:
            case BC_DOUBLE_NUM_0:
                return 0;
            case BC_TRUE:
            case BC_DOUBLE_NUM_1:
                return 1;
            case BC_INT8:
                return bytes[offset++];
            case BC_INT16:
                int int16Value =
                        ((bytes[offset + 1] & 0xFF) +
                                (bytes[offset] << 8));
                offset += 2;
                return int16Value;
            case BC_DOUBLE_LONG: {
                return (int) readInt64Value();
            }
            case BC_INT64: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return (int) (BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
            }
            case BC_FLOAT_INT:
                return (int) (float) readInt32Value();
            case BC_FLOAT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                float floatValue = Float.intBitsToFloat(int32Value);
                return (int) floatValue;
            }
            case BC_DOUBLE: {
                offset--;
                return (int) readDoubleValue();
            }
            case BC_TIMESTAMP_MINUTES:
            case BC_TIMESTAMP_SECONDS:
            case BC_INT64_INT:
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return int32Value;
            case BC_STR_ASCII: {
                int strlen = readInt32Value();
                String str = getLatin1String(offset, strlen);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF16LE: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16LE);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF8: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                BigDecimal decimal;
                if (scale == 0) {
                    decimal = new BigDecimal(unscaledValue);
                } else {
                    decimal = new BigDecimal(unscaledValue, scale);
                }
                return decimal.intValue();
            }
            default:
                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    int strlen = type - BC_STR_ASCII_FIX_MIN;
                    String str = getLatin1String(offset, strlen);
                    offset += strlen;
                    if (str.indexOf('.') == -1) {
                        return new BigInteger(str).intValue();
                    } else {
                        return toBigDecimal(str).intValue();
                    }
                }
                break;
        }
        throw new JSONException("readInt32Value not support " + typeName(type) + ", offset " + offset + "/" + bytes.length);
    }

    @Override
    public boolean isBinary() {
        return bytes[offset] == BC_BINARY;
    }

    @Override
    public byte[] readBinary() {
        byte type = bytes[offset++];
        if (type != BC_BINARY) {
            throw notSupportType(type);
        }

        int len = readLength();
        byte[] bytes = new byte[len];
        System.arraycopy(this.bytes, offset, bytes, 0, len);
        offset += len;
        return bytes;
    }

    @Override
    public Integer readInt32() {
        final byte[] bytes = this.bytes;
        int offset = this.offset;
        byte type = bytes[offset++];

        if (type == BC_NULL) {
            this.offset = offset;
            return null;
        }

        int int32Value;
        if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
            int32Value = type;
        } else if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
            int32Value = ((type - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset] & 0xFF);
            offset += 1;
        } else if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
            int32Value = ((type - BC_INT32_SHORT_ZERO) << 16)
                    + ((bytes[offset] & 0xFF) << 8)
                    + (bytes[offset + 1] & 0xFF);
            offset += 2;
        } else if (type == BC_INT32) {
            int32Value = UNSAFE.getInt(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
            if (!BIG_ENDIAN) {
                int32Value = Integer.reverseBytes(int32Value);
            }
            offset += 4;
        } else {
            this.offset = offset;
            return readInt32Value0(bytes, type);
        }
        this.offset = offset;
        return int32Value;
    }

    @Override
    public Long readInt64() {
        final byte[] bytes = this.bytes;
        int offset = this.offset;
        byte type = bytes[offset++];

        if (type == BC_NULL) {
            this.offset = offset;
            return null;
        }

        long int64Value;
        if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
            int64Value = INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
        } else if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
            int64Value = ((type - BC_INT64_BYTE_ZERO) << 8)
                    + (bytes[offset] & 0xFF);
            offset += 1;
        } else if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
            int64Value = ((type - BC_INT64_SHORT_ZERO) << 16)
                    + ((bytes[offset] & 0xFF) << 8)
                    + (bytes[offset + 1] & 0xFF);
            offset += 2;
        } else if (type == BC_INT64_INT) {
            int int32Val = UNSAFE.getInt(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
            int64Value = BIG_ENDIAN ? int32Val : Integer.reverseBytes(int32Val);
            offset += 4;
        } else if (type == BC_INT64) {
            int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
            if (!BIG_ENDIAN) {
                int64Value = Long.reverseBytes(int64Value);
            }
            offset += 8;
        } else {
            this.offset = offset;
            return readInt64Value0(bytes, type);
        }
        this.offset = offset;
        return int64Value;
    }

    @Override
    public float readFloatValue() {
        byte[] bytes = this.bytes;
        int offset = this.offset;
        if (bytes[offset] == BC_FLOAT) {
            int int32Val = UNSAFE.getInt(bytes, ARRAY_BYTE_BASE_OFFSET + offset + 1);
            this.offset = offset + 5;
            return Float.intBitsToFloat(BIG_ENDIAN ? int32Val : Integer.reverseBytes(int32Val));
        }

        return readFloat0();
    }

    private float readFloat0() {
        final byte[] bytes = this.bytes;
        byte type = bytes[offset++];
        switch (type) {
            case BC_NULL:
                if ((context.features & Feature.ErrorOnNullForPrimitives.mask) != 0) {
                    throw new JSONException(info("long value not support input null"));
                }
                wasNull = true;
                return 0;
            case BC_INT8:
                return bytes[offset++];
            case BC_INT16:
                int int16Value =
                        ((bytes[offset + 1] & 0xFF) +
                                (bytes[offset] << 8));
                offset += 2;
                return int16Value;
            case BC_INT64: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return (float) (BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
            }
            case BC_INT64_INT:
            case BC_INT32: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return int32Value;
            }
            case BC_DOUBLE: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return (float) Double.longBitsToDouble(BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
            }
            case BC_FLOAT_INT: {
                return (float) readInt32Value();
            }
            case BC_DOUBLE_LONG: {
                return (float) (double) readInt64Value();
            }
            case BC_STR_ASCII: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, ISO_8859_1);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF16LE: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16LE);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF8: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                BigDecimal decimal;
                if (scale == 0) {
                    decimal = new BigDecimal(unscaledValue);
                } else {
                    decimal = new BigDecimal(unscaledValue, scale);
                }
                return decimal.intValue();
            }
            case BC_FALSE:
//            case FLOAT_NUM_0:
            case BC_DOUBLE_NUM_0:
                return 0;
            case BC_TRUE:
//            case FLOAT_NUM_1:
            case BC_DOUBLE_NUM_1:
                return 1;
            default:
                if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                    return type;
                }

                if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                    return ((type - BC_INT32_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
                    int int3 = getInt3(bytes, offset, type);
                    this.offset = offset + 2;
                    return int3;
                }

                if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
                    return (float) (INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN));
                }

                if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
                    return ((type - BC_INT64_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
                    return ((type - BC_INT64_SHORT_ZERO) << 16)
                            + ((bytes[offset++] & 0xFF) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    int strlen = type - BC_STR_ASCII_FIX_MIN;
                    String str = getLatin1String(offset, strlen);
                    offset += strlen;
                    if (str.indexOf('.') == -1) {
                        return new BigInteger(str).intValue();
                    } else {
                        return toBigDecimal(str).intValue();
                    }
                }
                break;
        }
        throw notSupportType(type);
    }

    @Override
    public double readDoubleValue() {
        byte[] bytes = this.bytes;
        int offset = this.offset;
        if (bytes[offset] == BC_DOUBLE) {
            long int64Value = ((bytes[offset + 8] & 0xFFL)) +
                    ((bytes[offset + 7] & 0xFFL) << 8) +
                    ((bytes[offset + 6] & 0xFFL) << 16) +
                    ((bytes[offset + 5] & 0xFFL) << 24) +
                    ((bytes[offset + 4] & 0xFFL) << 32) +
                    ((bytes[offset + 3] & 0xFFL) << 40) +
                    ((bytes[offset + 2] & 0xFFL) << 48) +
                    ((long) (bytes[offset + 1]) << 56);
            this.offset = offset + 9;
            return Double.longBitsToDouble(int64Value);
        }

        return readDoubleValue0();
    }

    private double readDoubleValue0() {
        final byte[] bytes = this.bytes;
        byte type = bytes[offset++];
        switch (type) {
            case BC_NULL:
                if ((context.features & Feature.ErrorOnNullForPrimitives.mask) != 0) {
                    throw new JSONException(info("long value not support input null"));
                }
                wasNull = true;
                return 0;
            case BC_INT8:
                return bytes[offset++];
            case BC_INT16:
                int int16Value =
                        ((bytes[offset + 1] & 0xFF) +
                                (bytes[offset] << 8));
                offset += 2;
                return int16Value;
            case BC_INT64: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return (double) (BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
            }
            case BC_INT64_INT:
            case BC_INT32: {
                int int32Value = UNSAFE.getInt(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 4;
                return BIG_ENDIAN ? int32Value : Integer.reverseBytes(int32Value);
            }
            case BC_FLOAT:
                int int32Value = UNSAFE.getInt(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 4;
                return Float.intBitsToFloat(BIG_ENDIAN ? int32Value : Integer.reverseBytes(int32Value));
            case BC_FLOAT_INT: {
                return (float) readInt32Value();
            }
            case BC_DOUBLE_LONG:
                return readInt64Value();
            case BC_STR_ASCII: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, ISO_8859_1);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF16LE: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16LE);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_STR_UTF8: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str).intValue();
                } else {
                    return toBigDecimal(str).intValue();
                }
            }
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                BigDecimal decimal;
                if (scale == 0) {
                    decimal = new BigDecimal(unscaledValue);
                } else {
                    decimal = new BigDecimal(unscaledValue, scale);
                }
                return decimal.intValue();
            }
            case BC_FALSE:
//            case FLOAT_NUM_0:
            case BC_DOUBLE_NUM_0:
                return 0;
//            case FLOAT_NUM_1:
            case BC_DOUBLE_NUM_1:
            case BC_TRUE:
                return 1;
            default:
                if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                    return type;
                }

                if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                    return ((type - BC_INT32_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
                    int int3 = getInt3(bytes, offset, type);
                    this.offset = offset + 2;
                    return int3;
                }

                if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
                    return (long) INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
                }

                if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
                    return ((type - BC_INT64_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
                    return ((type - BC_INT64_SHORT_ZERO) << 16)
                            + ((bytes[offset++] & 0xFF) << 8)
                            + (bytes[offset++] & 0xFF);
                }

                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    int strlen = type - BC_STR_ASCII_FIX_MIN;
                    String str = getLatin1String(offset, strlen);
                    offset += strlen;
                    if (str.indexOf('.') == -1) {
                        return new BigInteger(str).intValue();
                    } else {
                        return toBigDecimal(str).intValue();
                    }
                }
                break;
        }
        throw notSupportType(type);
    }

    @Override
    protected void readNumber0() {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public Number readNumber() {
        byte type = bytes[offset++];
        if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
            return (int) type;
        }

        if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
            return ((type - BC_INT32_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
        }

        if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
            int int3 = getInt3(bytes, offset, type);
            this.offset = offset + 2;
            return int3;
        }

        if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
            return (long) INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
        }

        if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
            return ((type - BC_INT64_BYTE_ZERO) << 8)
                    + (bytes[offset++] & 0xFF);
        }

        if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
            return ((type - BC_INT64_SHORT_ZERO) << 16)
                    + ((bytes[offset++] & 0xFF) << 8)
                    + (bytes[offset++] & 0xFF);
        }

        switch (type) {
            case BC_NULL:
                return null;
            case BC_FALSE:
            case BC_DOUBLE_NUM_0:
                return 0D;
            case BC_TRUE:
            case BC_DOUBLE_NUM_1:
                return 1D;
            case BC_INT8:
                return bytes[offset++];
            case BC_INT16: {
                int int16Value =
                        ((bytes[offset + 1] & 0xFF) +
                                (bytes[offset] << 8));
                offset += 2;
                return (short) int16Value;
            }
            case BC_INT32: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return int32Value;
            }
            case BC_INT64_INT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return (long) int32Value;
            }
            case BC_INT64: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value);
            }
            case BC_BIGINT: {
                int len = readInt32Value();
                byte[] bytes = new byte[len];
                System.arraycopy(this.bytes, offset, bytes, 0, len);
                offset += len;
                return new BigInteger(bytes);
            }
            case BC_BIGINT_LONG: {
                return BigInteger.valueOf(
                        readInt64Value()
                );
            }
            case BC_FLOAT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return Float.intBitsToFloat(int32Value);
            }
            case BC_FLOAT_INT: {
                return (float) readInt32Value();
            }
            case BC_DOUBLE:
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return Double.longBitsToDouble(
                        BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value)
                );
            case BC_DOUBLE_LONG:
                return (double) readInt64Value();
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                if (scale == 0) {
                    return new BigDecimal(unscaledValue);
                } else {
                    return new BigDecimal(unscaledValue, scale);
                }
            }
            case BC_DECIMAL_LONG: {
                return BigDecimal.valueOf(
                        readInt64Value()
                );
            }
            case BC_STR_ASCII: {
                int strlen = readInt32Value();
                String str = getLatin1String(offset, strlen);
                offset += strlen;
                return toBigDecimal(str);
            }
            case BC_STR_UTF8: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                offset += strlen;
                return toBigDecimal(str);
            }
            case BC_TYPED_ANY: {
                String typeName = readString();
                throw new JSONException("not support input type : ".concat(typeName));
            }
            default:
                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    int strlen = type - BC_STR_ASCII_FIX_MIN;
                    String str = getLatin1String(offset, strlen);
                    offset += strlen;
                    return toBigDecimal(str);
                }
                break;
        }
        throw notSupportType(type);
    }

    @Override
    public BigDecimal readBigDecimal() {
        final byte[] bytes = this.bytes;
        byte type = bytes[offset++];
        BigDecimal decimal;
        if (type == BC_DECIMAL) {
            int scale = readInt32Value();
            if (bytes[offset] == BC_BIGINT_LONG) {
                offset++;
                long unscaledLongValue = readInt64Value();
                decimal = BigDecimal.valueOf(unscaledLongValue, scale);
            } else if (bytes[offset] == BC_INT32) {
                decimal = BigDecimal.valueOf(getInt(bytes, offset + 1), scale);
                offset += 5;
            } else if (bytes[offset] == BC_INT64) {
                long unscaledValue = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset + 1);
                decimal = BigDecimal.valueOf(
                        BIG_ENDIAN
                                ? unscaledValue
                                : Long.reverseBytes(unscaledValue), scale
                );
                offset += 9;
            } else {
                BigInteger unscaledValue = readBigInteger();
                decimal = scale == 0
                        ? new BigDecimal(unscaledValue)
                        : new BigDecimal(unscaledValue, scale);
            }
        } else if (type == BC_DECIMAL_LONG) {
            decimal = BigDecimal.valueOf(
                    readInt64Value()
            );
        } else {
            decimal = readDecimal0(type);
        }
        return decimal;
    }

    private BigDecimal readDecimal0(byte type) {
        switch (type) {
            case BC_NULL:
                return null;
            case BC_DOUBLE_NUM_0:
            case BC_FALSE:
                return BigDecimal.ZERO;
            case BC_DOUBLE_NUM_1:
            case BC_TRUE:
                return BigDecimal.ONE;
            case BC_INT8:
                return BigDecimal.valueOf(bytes[offset++]);
            case BC_INT16:
                int int16Value =
                        ((bytes[offset + 1] & 0xFF) +
                                (bytes[offset] << 8));
                offset += 2;
                return BigDecimal.valueOf(int16Value);
            case BC_INT64_INT:
            case BC_INT32: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return BigDecimal.valueOf(int32Value);
            }
            case BC_FLOAT_INT: {
                float floatValue = (float) readInt32Value();
                return BigDecimal.valueOf((long) floatValue);
            }
            case BC_FLOAT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                float floatValue = Float.intBitsToFloat(int32Value);
                return BigDecimal.valueOf((long) floatValue);
            }
            case BC_DOUBLE: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                double doubleValue = Double.longBitsToDouble(BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
                return BigDecimal.valueOf(
                        (long) doubleValue);
            }
            case BC_INT64: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return BigDecimal.valueOf(BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
            }
            case BC_BIGINT: {
                BigInteger bigInt = readBigInteger();
                return new BigDecimal(bigInt);
            }
            case BC_DOUBLE_LONG: {
                double doubleValue = readInt64Value();
                return BigDecimal.valueOf((long) doubleValue);
            }
            case BC_STR_ASCII: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, ISO_8859_1);
                offset += strlen;
                return toBigDecimal(str);
            }
            case BC_STR_UTF16LE: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16LE);
                offset += strlen;
                return toBigDecimal(str);
            }
            case BC_STR_UTF8: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                offset += strlen;
                return toBigDecimal(str);
            }
            default:
                if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                    return BigDecimal.valueOf(type);
                }

                if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                    int intValue = ((type - BC_INT32_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                    return BigDecimal.valueOf(intValue);
                }

                if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
                    int int3 = getInt3(bytes, offset, type);
                    this.offset = offset + 2;
                    return BigDecimal.valueOf(int3);
                }

                if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
                    int intValue = INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
                    return BigDecimal.valueOf(intValue);
                }

                if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
                    int intValue = ((type - BC_INT64_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                    return BigDecimal.valueOf(intValue);
                }

                if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
                    int intValue = ((type - BC_INT64_SHORT_ZERO) << 16)
                            + ((bytes[offset++] & 0xFF) << 8)
                            + (bytes[offset++] & 0xFF);
                    return BigDecimal.valueOf(intValue);
                }

                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    int strlen = type - BC_STR_ASCII_FIX_MIN;
                    String str = getLatin1String(offset, strlen);
                    offset += strlen;
                    return toBigDecimal(str);
                }
                break;
        }
        throw notSupportType(type);
    }

    @Override
    public BigInteger readBigInteger() {
        byte type = bytes[offset++];
        BigInteger bigInt;
        if (type == BC_BIGINT_LONG) {
            bigInt = BigInteger.valueOf(
                    readInt64Value()
            );
        } else if (type == BC_BIGINT) {
            int len = readInt32Value();
            byte[] bytes = new byte[len];
            System.arraycopy(this.bytes, offset, bytes, 0, len);
            offset += len;
            bigInt = new BigInteger(bytes);
        } else {
            bigInt = readBigInteger0(type);
        }
        return bigInt;
    }

    private BigInteger readBigInteger0(byte type) {
        final byte[] bytes = this.bytes;
        switch (type) {
            case BC_NULL:
                return null;
            case BC_DOUBLE_NUM_0:
            case BC_FALSE:
                return BigInteger.ZERO;
            case BC_DOUBLE_NUM_1:
            case BC_TRUE:
                return BigInteger.ONE;
            case BC_INT8:
                return BigInteger.valueOf(bytes[offset++]);
            case BC_INT16:
                int int16Value =
                        ((bytes[offset + 1] & 0xFF) +
                                (bytes[offset] << 8));
                offset += 2;
                return BigInteger.valueOf(int16Value);
            case BC_INT64_INT:
            case BC_INT32: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                return BigInteger.valueOf(int32Value);
            }
            case BC_FLOAT_INT: {
                float floatValue = (float) readInt32Value();
                return BigInteger.valueOf(
                        (long) floatValue);
            }
            case BC_FLOAT: {
                int int32Value = getInt(bytes, offset);
                offset += 4;
                float floatValue = Float.intBitsToFloat(int32Value);
                return BigInteger.valueOf(
                        (long) floatValue);
            }
            case BC_DOUBLE: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                double doubleValue = Double.longBitsToDouble(BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
                return BigInteger.valueOf(
                        (long) doubleValue);
            }
            case BC_INT64: {
                long int64Value = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return BigInteger.valueOf(BIG_ENDIAN ? int64Value : Long.reverseBytes(int64Value));
            }
            case BC_BINARY: {
                int len = readInt32Value();
                byte[] buf = new byte[len];
                System.arraycopy(this.bytes, offset, buf, 0, len);
                offset += len;
                return new BigInteger(buf);
            }
            case BC_DECIMAL: {
                int scale = readInt32Value();
                BigInteger unscaledValue = readBigInteger();
                BigDecimal decimal;
                if (scale == 0) {
                    decimal = new BigDecimal(unscaledValue);
                } else {
                    decimal = new BigDecimal(unscaledValue, scale);
                }
                return decimal.toBigInteger();
            }
            case BC_DOUBLE_LONG: {
                double doubleValue = readInt64Value();
                return BigInteger.valueOf((long) doubleValue);
            }
            case BC_STR_ASCII: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, ISO_8859_1);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str);
                } else {
                    return toBigDecimal(str).toBigInteger();
                }
            }
            case BC_STR_UTF8: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str);
                } else {
                    return toBigDecimal(str).toBigInteger();
                }
            }
            case BC_STR_UTF16LE: {
                int strlen = readInt32Value();
                String str = new String(bytes, offset, strlen, StandardCharsets.UTF_16LE);
                offset += strlen;
                if (str.indexOf('.') == -1) {
                    return new BigInteger(str);
                } else {
                    return toBigDecimal(str).toBigInteger();
                }
            }
            default:
                if (type >= BC_INT32_NUM_MIN && type <= BC_INT32_NUM_MAX) {
                    return BigInteger.valueOf(type);
                }

                if (type >= BC_INT32_BYTE_MIN && type <= BC_INT32_BYTE_MAX) {
                    int intValue = ((type - BC_INT32_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                    return BigInteger.valueOf(intValue);
                }

                if (type >= BC_INT32_SHORT_MIN && type <= BC_INT32_SHORT_MAX) {
                    int int3 = getInt3(bytes, offset, type);
                    this.offset = offset + 2;
                    return BigInteger.valueOf(int3);
                }

                if (type >= BC_INT64_NUM_MIN && type <= BC_INT64_NUM_MAX) {
                    int intValue = INT64_NUM_LOW_VALUE + (type - BC_INT64_NUM_MIN);
                    return BigInteger.valueOf(intValue);
                }

                if (type >= BC_INT64_BYTE_MIN && type <= BC_INT64_BYTE_MAX) {
                    int intValue = ((type - BC_INT64_BYTE_ZERO) << 8)
                            + (bytes[offset++] & 0xFF);
                    return BigInteger.valueOf(intValue);
                }

                if (type >= BC_INT64_SHORT_MIN && type <= BC_INT64_SHORT_MAX) {
                    int intValue = ((type - BC_INT64_SHORT_ZERO) << 16)
                            + ((bytes[offset++] & 0xFF) << 8)
                            + (bytes[offset++] & 0xFF);
                    return BigInteger.valueOf(intValue);
                }

                if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
                    int strlen = type - BC_STR_ASCII_FIX_MIN;
                    String str = getLatin1String(offset, strlen);
                    offset += strlen;
                    return new BigInteger(str);
                }
                break;
        }
        throw notSupportType(type);
    }

    @Override
    public LocalDate readLocalDate() {
        int type = bytes[offset];
        if (type == BC_LOCAL_DATE) {
            offset++;
            int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
            int month = bytes[offset++];
            int dayOfMonth = bytes[offset++];
            return LocalDate.of(year, month, dayOfMonth);
        }

        if (type == BC_NULL) {
            offset++;
            return null;
        }

        return readLocalDate0(type);
    }

    private LocalDate readLocalDate0(int type) {
        if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
            int len = getStringLength();
            switch (len) {
                case 8:
                    return readLocalDate8();
                case 9:
                    return readLocalDate9();
                case 10:
                    return readLocalDate10();
                case 11:
                    return readLocalDate11();
                default:
                    break;
            }
            throw new JSONException("TODO : " + len + ", " + readString());
        }

        if (type == BC_STR_UTF8 || type == BC_STR_ASCII) {
            strtype = (byte) type;
            offset++;
            strlen = readLength();
            switch (strlen) {
                case 8:
                    return readLocalDate8();
                case 9:
                    return readLocalDate9();
                case 10: {
                    return readLocalDate10();
                }
                case 11:
                    return readLocalDate11();
                default:
                    break;
            }
        }

        throw new UnsupportedOperationException();
    }

    @Override
    public LocalDateTime readLocalDateTime() {
        int type = bytes[offset];
        if (type == BC_LOCAL_DATETIME) {
            offset++;
            int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
            int month = bytes[offset++];
            int dayOfMonth = bytes[offset++];
            int hour = bytes[offset++];
            int minute = bytes[offset++];
            int second = bytes[offset++];

            int nano = readInt32Value();

            return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nano);
        }

        if (type == BC_NULL) {
            offset++;
            return null;
        }

        return readLocalDateTime0(type);
    }

    private LocalDateTime readLocalDateTime0(int type) {
        if (type == BC_TIMESTAMP_WITH_TIMEZONE) {
            return readZonedDateTime().toLocalDateTime();
        }

        if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
            int len = getStringLength();
            LocalDate localDate;
            switch (len) {
                case 8:
                    localDate = readLocalDate8();
                    return localDate == null ? null : LocalDateTime.of(localDate, LocalTime.MIN);
                case 9:
                    localDate = readLocalDate9();
                    return localDate == null ? null : LocalDateTime.of(localDate, LocalTime.MIN);
                case 10:
                    localDate = readLocalDate10();
                    return localDate == null ? null : LocalDateTime.of(localDate, LocalTime.MIN);
                case 11:
                    localDate = readLocalDate11();
                    return localDate == null ? null : LocalDateTime.of(localDate, LocalTime.MIN);
                case 16:
                    return readLocalDateTime16();
                case 17:
                    return readLocalDateTime17();
                case 18:
                    return readLocalDateTime18();
                case 19:
                    return readLocalDateTime19();
                case 20:
                    return readLocalDateTime20();
                case 21:
                case 22:
                case 23:
                case 24:
                case 25:
                case 26:
                case 27:
                case 28:
                case 29:
                    LocalDateTime ldt = readLocalDateTimeX(len);
                    if (ldt != null) {
                        return ldt;
                    }
                    ZonedDateTime zdt = readZonedDateTimeX(len);
                    if (zdt != null) {
                        return zdt.toLocalDateTime();
                    }
                    break;
                default:
                    break;
            }

            throw new JSONException("TODO : " + len + ", " + readString());
        }

        throw new JSONException("not support type : " + typeName((byte) type));
    }

    @Override
    protected final LocalDateTime readLocalDateTime12() {
        LocalDateTime ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 12
                || (ldt = DateUtils.parseLocalDateTime12(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 13;
        return ldt;
    }

    @Override
    protected final LocalDateTime readLocalDateTime14() {
        LocalDateTime ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 14
                || (ldt = DateUtils.parseLocalDateTime14(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 15;
        return ldt;
    }

    @Override
    protected final LocalDateTime readLocalDateTime16() {
        LocalDateTime ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 16
                || (ldt = DateUtils.parseLocalDateTime16(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 17;
        return ldt;
    }

    @Override
    protected final LocalDateTime readLocalDateTime17() {
        LocalDateTime ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 17
                || (ldt = DateUtils.parseLocalDateTime17(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 18;
        return ldt;
    }

    @Override
    protected LocalTime readLocalTime10() {
        LocalTime time;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 10
                || (time = DateUtils.parseLocalTime10(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 11;
        return time;
    }

    @Override
    protected final LocalTime readLocalTime11() {
        LocalTime time;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 11
                || (time = DateUtils.parseLocalTime11(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 12;
        return time;
    }

    @Override
    protected final ZonedDateTime readZonedDateTimeX(int len) {
        type = bytes[offset];
        if (type < BC_STR_ASCII_FIX_MIN || type > BC_STR_ASCII_FIX_MAX) {
            throw new JSONException("date only support string input");
        }

        ZonedDateTime ldt;
        if (len < 19
                || (ldt = DateUtils.parseZonedDateTime(bytes, offset + 1, len, context.zoneId)) == null
        ) {
            throw new JSONException("illegal LocalDateTime string : " + readString());
        }

        offset += (len + 1);
        return ldt;
    }

    @Override
    public void skipComment() {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public LocalTime readLocalTime() {
        int type = bytes[offset];
        if (type == BC_LOCAL_TIME) {
            offset++;
            int hour = bytes[offset++];
            int minute = bytes[offset++];
            int second = bytes[offset++];
            int nano = readInt32Value();
            return LocalTime.of(hour, minute, second, nano);
        }

        if (type >= BC_STR_ASCII_FIX_MIN && type <= BC_STR_ASCII_FIX_MAX) {
            int len = getStringLength();
            switch (len) {
                case 5:
                    return readLocalTime5();
                case 8:
                    return readLocalTime8();
                case 10:
                    return readLocalTime10();
                case 11:
                    return readLocalTime11();
                case 12:
                    return readLocalTime12();
                case 18:
                    return readLocalTime18();
                default:
                    break;
            }
            throw new JSONException("not support len : " + len);
        }

        throw new UnsupportedOperationException();
    }

    @Override
    public Instant readInstant() {
        int type = bytes[offset++];
        switch (type) {
            case BC_TIMESTAMP: {
                long second = readInt64Value();
                int nano = readInt32Value();
                return Instant.ofEpochSecond(second, nano);
            }
            case BC_TIMESTAMP_MINUTES: {
                long minutes = getInt(bytes, offset);
                offset += 4;
                return Instant.ofEpochSecond(minutes * 60, 0);
            }
            case BC_TIMESTAMP_SECONDS: {
                long seconds = getInt(bytes, offset);
                offset += 4;
                return Instant.ofEpochSecond(seconds, 0);
            }
            case BC_INT64:
            case BC_TIMESTAMP_MILLIS: {
                long millis = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                return Instant.ofEpochMilli(BIG_ENDIAN ? millis : Long.reverseBytes(millis));
            }
            default:
                break;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public OffsetTime readOffsetTime() {
        ZonedDateTime zdt = readZonedDateTime();
        return zdt == null ? null : zdt.toOffsetDateTime().toOffsetTime();
    }

    @Override
    public OffsetDateTime readOffsetDateTime() {
        ZonedDateTime zdt = readZonedDateTime();
        return zdt == null ? null : zdt.toOffsetDateTime();
    }

    @Override
    public ZonedDateTime readZonedDateTime() {
        int type = bytes[offset++];
        if (type == BC_TIMESTAMP_WITH_TIMEZONE) {
            int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
            int month = bytes[offset++];
            int dayOfMonth = bytes[offset++];
            int hour = bytes[offset++];
            int minute = bytes[offset++];
            int second = bytes[offset++];
            int nano = readInt32Value();
            LocalDateTime ldt = LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nano);

            ZoneId zoneId;
            long zoneIdHash = readValueHashCode();
            final long SHANGHAI_ZONE_ID_HASH = -4800907791268808639L; // Fnv.hashCode64("Asia/Shanghai");
            if (zoneIdHash == SHANGHAI_ZONE_ID_HASH) {
                zoneId = SHANGHAI_ZONE_ID;
            } else {
                String zoneIdStr = getString();
                ZoneId contextZoneId = context.getZoneId();
                if (contextZoneId.getId().equals(zoneIdStr)) {
                    zoneId = contextZoneId;
                } else {
                    zoneId = DateUtils.getZoneId(zoneIdStr, SHANGHAI_ZONE_ID);
                }
            }
            return ZonedDateTime.ofLocal(ldt, zoneId, null);
        }

        return readZonedDateTime0(type);
    }

    private ZonedDateTime readZonedDateTime0(int type) {
        switch (type) {
            case BC_NULL:
                return null;
            case BC_TIMESTAMP: {
                long second = readInt64Value();
                int nano = readInt32Value();
                Instant instant = Instant.ofEpochSecond(second, nano);
                return ZonedDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
            }
            case BC_TIMESTAMP_MINUTES: {
                long minutes = getInt(bytes, offset);
                offset += 4;
                Instant instant = Instant.ofEpochSecond(minutes * 60);
                return ZonedDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
            }
            case BC_TIMESTAMP_SECONDS: {
                long seconds = getInt(bytes, offset);
                offset += 4;
                Instant instant = Instant.ofEpochSecond(seconds);
                return ZonedDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
            }
            case BC_LOCAL_DATE: {
                int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
                byte month = bytes[offset++];
                byte dayOfMonth = bytes[offset++];
                LocalDate localDate = LocalDate.of(year, month, dayOfMonth);
                return ZonedDateTime.of(localDate, LocalTime.MIN, DEFAULT_ZONE_ID);
            }
            case BC_LOCAL_DATETIME: {
                int year = (bytes[offset++] << 8) + (bytes[offset++] & 0xFF);
                byte month = bytes[offset++];
                byte dayOfMonth = bytes[offset++];
                byte hour = bytes[offset++];
                byte minute = bytes[offset++];
                byte second = bytes[offset++];
                int nano = readInt32Value();
                LocalDateTime ldt = LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nano);
                return ZonedDateTime.of(ldt, DEFAULT_ZONE_ID);
            }
            case BC_INT64:
            case BC_TIMESTAMP_MILLIS: {
                long millis = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                offset += 8;
                Instant instant = Instant.ofEpochMilli(BIG_ENDIAN ? millis : Long.reverseBytes(millis));
                return ZonedDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
            }
            default:
                if (type >= BC_STR_ASCII_FIX_0 && type <= BC_STR_ASCII_FIX_MAX) {
                    offset--;
                    return readZonedDateTimeX(type - BC_STR_ASCII_FIX_MIN);
                }
                break;
        }
        throw new JSONException("type not support : " + JSONB.typeName((byte) type));
    }

    @Override
    public UUID readUUID() {
        final byte[] bytes = this.bytes;
        byte type = bytes[offset++];
        long hi = 0, lo = 0;
        switch (type) {
            case BC_NULL:
                return null;
            case BC_BINARY:
                int len = readLength();
                if (len != 16) {
                    throw new JSONException("uuid not support " + len);
                }
                long msb = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
                long lsb = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset + 8);
                offset += 16;
                hi = BIG_ENDIAN ? msb : Long.reverseBytes(msb);
                lo = BIG_ENDIAN ? lsb : Long.reverseBytes(lsb);
                break;
            case BC_STR_ASCII_FIX_32: {
                for (int i = 0; i < 16; i++) {
                    hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                }
                for (int i = 16; i < 32; i++) {
                    lo = (lo << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                }

                offset += 32;
                break;
            }
            case BC_STR_ASCII_FIX_36: {
                byte ch1 = bytes[offset + 8];
                byte ch2 = bytes[offset + 13];
                byte ch3 = bytes[offset + 18];
                byte ch4 = bytes[offset + 23];
                if (ch1 == '-' && ch2 == '-' && ch3 == '-' && ch4 == '-') {
                    for (int i = 0; i < 8; i++) {
                        hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                    }
                    for (int i = 9; i < 13; i++) {
                        hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                    }
                    for (int i = 14; i < 18; i++) {
                        hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                    }

                    for (int i = 19; i < 23; i++) {
                        lo = (lo << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                    }
                    for (int i = 24; i < 36; i++) {
                        lo = (lo << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                    }

                    offset += 36;
                    break;
                }
                throw new JSONException("Invalid UUID string:  " + new String(bytes, offset, 36, ISO_8859_1));
            }
            case BC_STR_ASCII:
            case BC_STR_UTF8: {
                int strlen = readLength();
                if (strlen == 32) {
                    for (int i = 0; i < 16; i++) {
                        hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                    }
                    for (int i = 16; i < 32; i++) {
                        lo = (lo << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                    }

                    offset += 32;
                } else if (strlen == 36) {
                    byte ch1 = bytes[offset + 8];
                    byte ch2 = bytes[offset + 13];
                    byte ch3 = bytes[offset + 18];
                    byte ch4 = bytes[offset + 23];
                    if (ch1 == '-' && ch2 == '-' && ch3 == '-' && ch4 == '-') {
                        for (int i = 0; i < 8; i++) {
                            hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                        }
                        for (int i = 9; i < 13; i++) {
                            hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                        }
                        for (int i = 14; i < 18; i++) {
                            hi = (hi << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                        }

                        for (int i = 19; i < 23; i++) {
                            lo = (lo << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                        }
                        for (int i = 24; i < 36; i++) {
                            lo = (lo << 4) + UUID_VALUES[bytes[offset + i] - '0'];
                        }

                        offset += 36;
                    }
                } else {
                    String str = new String(bytes, offset, strlen, StandardCharsets.UTF_8);
                    offset += strlen;
                    throw new JSONException("Invalid UUID string:  " + str);
                }
                break;
            }
            default:
                throw notSupportType(type);
        }
        return new UUID(hi, lo);
    }

    public Boolean readBool() {
        byte type = this.bytes[offset++];
        if (type == BC_NULL) {
            return null;
        } else if (type == BC_TRUE) {
            return true;
        } else if (type == BC_FALSE) {
            return false;
        }

        return readBoolValue0(type);
    }

    @Override
    public boolean readBoolValue() {
        wasNull = false;
        byte type = this.bytes[offset++];
        if (type == BC_TRUE) {
            return true;
        } else if (type == BC_FALSE) {
            return false;
        }

        return readBoolValue0(type);
    }

    private boolean readBoolValue0(byte type) {
        final byte[] bytes = this.bytes;
        switch (type) {
            case BC_INT32_NUM_1:
                return true;
            case BC_INT32_NUM_0:
                return false;
            case BC_NULL:
                if ((context.features & Feature.ErrorOnNullForPrimitives.mask) != 0) {
                    throw new JSONException(info("long value not support input null"));
                }
                wasNull = true;
                return false;
            case BC_STR_ASCII_FIX_1:
                if (bytes[offset] == '1' || bytes[offset] == 'Y') {
                    offset++;
                    return true;
                }
                if (bytes[offset] == '0' || bytes[offset] == 'N') {
                    offset++;
                    return false;
                }
            case BC_STR_ASCII_FIX_4:
                if (bytes[offset] == 't'
                        && bytes[offset + 1] == 'r'
                        && bytes[offset + 2] == 'u'
                        && bytes[offset + 3] == 'e'
                ) {
                    offset += 4;
                    return true;
                }
                if (bytes[offset] == 'T'
                        && bytes[offset + 1] == 'R'
                        && bytes[offset + 2] == 'U'
                        && bytes[offset + 3] == 'E'
                ) {
                    offset += 4;
                    return true;
                }
            case BC_STR_ASCII_FIX_5:
                if (bytes[offset] == 'f'
                        && bytes[offset + 1] == 'a'
                        && bytes[offset + 2] == 'l'
                        && bytes[offset + 3] == 's'
                        && bytes[offset + 4] == 'e'
                ) {
                    offset += 5;
                    return false;
                }
                if (bytes[offset] == 'F'
                        && bytes[offset + 1] == 'A'
                        && bytes[offset + 2] == 'L'
                        && bytes[offset + 3] == 'S'
                        && bytes[offset + 4] == 'E'
                ) {
                    offset += 5;
                    return false;
                }
            case BC_STR_UTF8:
            case BC_STR_ASCII: {
                strlen = readLength();
                if (strlen == 1) {
                    if (bytes[offset] == 'Y') {
                        offset++;
                        return true;
                    }
                    if (bytes[offset] == 'N') {
                        offset++;
                        return true;
                    }
                } else if (strlen == 4
                        && bytes[offset] == 't'
                        && bytes[offset + 1] == 'r'
                        && bytes[offset + 2] == 'u'
                        && bytes[offset + 3] == 'e'
                ) {
                    offset += 4;
                    return true;
                } else if (strlen == 5) {
                    if (bytes[offset] == 'f'
                            && bytes[offset + 1] == 'a'
                            && bytes[offset + 2] == 'l'
                            && bytes[offset + 3] == 's'
                            && bytes[offset + 4] == 'e') {
                        offset += 5;
                        return false;
                    } else if (bytes[offset] == 'F'
                            && bytes[offset + 1] == 'A'
                            && bytes[offset + 2] == 'L'
                            && bytes[offset + 3] == 'S'
                            && bytes[offset + 4] == 'E') {
                        offset += 5;
                        return false;
                    }
                }
                String str = new String(bytes, offset, strlen, ISO_8859_1);
                offset += strlen;
                throw new JSONException("not support input " + str);
            }
            case BC_STR_UTF16:
            case BC_STR_UTF16BE:
            case BC_STR_UTF16LE: {
                strlen = readLength();
                byte[] chars = new byte[strlen];
                System.arraycopy(bytes, offset, chars, 0, strlen);

                Charset charset = type == BC_STR_UTF16BE
                        ? StandardCharsets.UTF_16BE
                        : type == BC_STR_UTF16LE ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16;

                String str = new String(chars, charset);
                offset += strlen;

                switch (str) {
                    case "0":
                    case "N":
                    case "false":
                    case "FALSE":
                        return false;
                    case "1":
                    case "Y":
                    case "true":
                    case "TRUE":
                        return true;
                    default:
                        throw new JSONException("not support input " + str);
                }
            }
            default:
                throw notSupportType(type);
        }
    }

    @Override
    public boolean nextIfMatch(byte type) {
        if (bytes[offset] == type) {
            offset++;
            return true;
        }
        return false;
    }

    public boolean nextIfMatchTypedAny() {
        if (bytes[offset] == BC_TYPED_ANY) {
            offset++;
            return true;
        }
        return false;
    }

    @Override
    protected final int getStringLength() {
        type = bytes[offset];
        if (type >= BC_STR_ASCII_FIX_MIN && type < BC_STR_ASCII_FIX_MAX) {
            return type - BC_STR_ASCII_FIX_MIN;
        }
        throw new UnsupportedOperationException(typeName(type));
    }

    public boolean isDate() {
        byte type = bytes[offset];
        return type >= BC_LOCAL_TIME && type <= BC_TIMESTAMP;
    }

    public Date readDate() {
        ZonedDateTime zdt = null;
        int offset = this.offset;
        byte[] bytes = this.bytes;
        byte type = bytes[offset];
        switch (type) {
            case BC_LOCAL_TIME: {
                LocalTime localTime = readLocalTime();
                LocalDateTime ldt = LocalDateTime.of(LocalDate.of(1970, 1, 1), localTime);
                zdt = ZonedDateTime.ofLocal(ldt, context.getZoneId(), null);
                break;
            }
            case BC_LOCAL_DATETIME:
                LocalDateTime ldt = readLocalDateTime();
                zdt = ZonedDateTime.ofLocal(ldt, context.getZoneId(), null);
                break;
            case BC_LOCAL_DATE:
                LocalDate localDate = readLocalDate();
                zdt = ZonedDateTime.ofLocal(
                        LocalDateTime.of(localDate, LocalTime.MIN),
                        context.getZoneId(),
                        null);
                break;
            case BC_TIMESTAMP_MILLIS: {
                long millis = UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset + 1);
                this.offset += 9;
                return new Date(BIG_ENDIAN ? millis : Long.reverseBytes(millis));
            }
            case BC_TIMESTAMP_MINUTES: {
                long minutes = getInt(bytes, offset + 1);
                this.offset += 5;
                return new Date(minutes * 60L * 1000L);
            }
            case BC_TIMESTAMP_SECONDS: {
                long seconds = getInt(bytes, offset + 1);
                this.offset += 5;
                return new Date(seconds * 1000);
            }
            case BC_TIMESTAMP_WITH_TIMEZONE: {
                this.offset = offset + 1;
                zdt = readTimestampWithTimeZone();
                break;
            }
            case BC_TIMESTAMP: {
                this.offset = offset + 1;
                long epochSeconds = readInt64Value();
                int nano = readInt32Value();
                return Date.from(
                        Instant.ofEpochSecond(epochSeconds, nano));
            }
            default:
                break;
        }

        if (zdt != null) {
            long seconds = zdt.toEpochSecond();
            int nanos = zdt.toLocalTime().getNano();
            long millis;
            if (seconds < 0 && nanos > 0) {
                millis = (seconds + 1) * 1000;
                long adjustment = nanos / 1000_000 - 1000;
                millis += adjustment;
            } else {
                millis = seconds * 1000L;
                millis += nanos / 1000_000;
            }
            return new Date(millis);
        }
        return super.readDate();
    }

    @Override
    public LocalDate readLocalDate8() {
        LocalDate ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 8
                || (ldt = DateUtils.parseLocalDate8(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }

        offset += 9;
        return ldt;
    }

    @Override
    public LocalDate readLocalDate9() {
        LocalDate ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 9
                || (ldt = DateUtils.parseLocalDate9(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }

        offset += 10;
        return ldt;
    }

    @Override
    protected final LocalDate readLocalDate10() {
        LocalDate ldt;
        if ((strtype == BC_STR_ASCII || strtype == BC_STR_UTF8) && strlen == 10) {
            ldt = DateUtils.parseLocalDate10(bytes, offset);
        } else if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 10
                || (ldt = DateUtils.parseLocalDate10(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }

        offset += 11;
        return ldt;
    }

    @Override
    protected final LocalDate readLocalDate11() {
        LocalDate ldt;
        if ((strtype == BC_STR_ASCII || strtype == BC_STR_UTF8) && strlen == 11) {
            ldt = DateUtils.parseLocalDate11(bytes, offset);
        } else if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 11
                || (ldt = DateUtils.parseLocalDate11(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }

        offset += 12;
        return ldt;
    }

    @Override
    protected final LocalTime readLocalTime5() {
        LocalTime time;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 5
                || (time = DateUtils.parseLocalTime5(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 6;
        return time;
    }

    @Override
    protected final LocalTime readLocalTime8() {
        LocalTime time;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 8
                || (time = DateUtils.parseLocalTime8(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 9;
        return time;
    }

    @Override
    protected LocalTime readLocalTime9() {
        LocalTime time;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 8
                || (time = DateUtils.parseLocalTime8(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 10;
        return time;
    }

    @Override
    protected final LocalTime readLocalTime12() {
        LocalTime time;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 12
                || (time = DateUtils.parseLocalTime12(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 13;
        return time;
    }

    @Override
    protected final LocalTime readLocalTime18() {
        LocalTime time;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 18
                || (time = DateUtils.parseLocalTime18(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 19;
        return time;
    }

    @Override
    protected final LocalDateTime readLocalDateTime18() {
        LocalDateTime ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 18
                || (ldt = DateUtils.parseLocalDateTime18(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 19;
        return ldt;
    }

    @Override
    protected final LocalDateTime readLocalDateTime20() {
        LocalDateTime ldt;
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 20
                || (ldt = DateUtils.parseLocalDateTime20(bytes, offset + 1)) == null
        ) {
            throw new JSONException("date only support string input");
        }
        offset += 21;
        return ldt;
    }

    @Override
    public long readMillis19() {
        if (bytes[offset] != BC_STR_ASCII_FIX_MIN + 19) {
            throw new JSONException("date only support string input");
        }

        long millis = DateUtils.parseMillis19(bytes, offset + 1, context.zoneId);
        offset += 20;
        return millis;
    }

    @Override
    protected final LocalDateTime readLocalDateTime19() {
        type = bytes[offset];
        if (type != BC_STR_ASCII_FIX_MIN + 19) {
            throw new JSONException("date only support string input");
        }

        LocalDateTime ldt = DateUtils.parseLocalDateTime19(bytes, offset + 1);
        if (ldt == null) {
            throw new JSONException("date only support string input");
        }

        offset += 20;
        return ldt;
    }

    @Override
    protected final LocalDateTime readLocalDateTimeX(int len) {
        type = bytes[offset];
        if (type < BC_STR_ASCII_FIX_MIN || type > BC_STR_ASCII_FIX_MAX) {
            throw new JSONException("date only support string input");
        }

        LocalDateTime ldt;
        if (len < 21
                || len > 29
                || (ldt = DateUtils.parseLocalDateTimeX(bytes, offset + 1, len)) == null
        ) {
            throw new JSONException("illegal LocalDateTime string : " + readString());
        }

        offset += (len + 1);
        return ldt;
    }

    @Override
    public String readPattern() {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfMatchIdent(char c0, char c1, char c2) {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public long readFieldNameHashCodeUnquote() {
        return readFieldNameHashCode();
    }

    @Override
    public boolean nextIfSet() {
        return false;
    }

    @Override
    public boolean nextIfInfinity() {
        return false;
    }

    @Override
    public boolean nextIfMatchIdent(char c0, char c1, char c2, char c3) {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfMatchIdent(char c0, char c1, char c2, char c3, char c4) {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public boolean nextIfMatchIdent(char c0, char c1, char c2, char c3, char c4, char c5) {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public SavePoint mark() {
        return new SavePoint(this.offset, this.type);
    }

    @Override
    public void reset(SavePoint savePoint) {
        this.offset = savePoint.offset;
        this.type = (byte) savePoint.current;
    }

    @Override
    public void close() {
        byte[] valueBytes = this.valueBytes;
        if (valueBytes != null && valueBytes.length < CACHE_THRESHOLD) {
            BYTES_UPDATER.lazySet(cacheItem, valueBytes);
        }

        char[] nameChars = this.charBuf;
        if (nameChars != null && nameChars.length < CACHE_THRESHOLD) {
            CHARS_UPDATER.lazySet(cacheItem, nameChars);
        }
    }

    public boolean isEnd() {
        return offset >= end;
    }

    @Override
    public int getRawInt() {
        return offset + 3 < end
                ? UNSAFE.getInt(bytes, ARRAY_BYTE_BASE_OFFSET + offset)
                : 0;
    }

    @Override
    public long getRawLong() {
        return offset + 7 < end
                ? UNSAFE.getLong(bytes, ARRAY_BYTE_BASE_OFFSET + offset)
                : 0;
    }

    @Override
    public boolean nextIfName4Match2() {
        return false;
    }

    @Override
    public boolean nextIfName4Match3() {
        int offset = this.offset + 4;
        if (offset > end) {
            return false;
        }
        this.offset = offset;
        return true;
    }

    public boolean nextIfName4Match4(byte name1) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 5;
        if (offset > end || bytes[offset - 1] != name1) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match5(int name1) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 6;
        if (offset > end || UNSAFE.getShort(bytes, BASE + offset - 2) != name1) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match6(int name1) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 7;
        if (offset > end
                || (UNSAFE.getInt(bytes, BASE + offset - 3) & 0xFFFFFF) != name1) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match7(int name1) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 8;
        if (offset > end || UNSAFE.getInt(bytes, BASE + offset - 4) != name1) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match8(int name1, byte name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 9;
        if (offset >= end || UNSAFE.getInt(bytes, BASE + offset - 5) != name1 || bytes[offset - 1] != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match9(long name1) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 10;
        if (offset + 1 >= end || (UNSAFE.getLong(bytes, BASE + offset - 6) & 0xFFFFFFFFFFFFL) != name1) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    public boolean nextIfName4Match10(long name1) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 11;
        if (offset >= end || (UNSAFE.getLong(bytes, BASE + offset - 7) & 0xFFFFFFFFFFFFFFL) != name1) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    public boolean nextIfName4Match11(long name1) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 12;
        if (offset >= end || UNSAFE.getLong(bytes, BASE + offset - 8) != name1) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    public boolean nextIfName4Match12(long name1, byte name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 13;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 9) != name1
                || bytes[offset - 1] != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match13(long name1, int name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 14;
        if (offset + 1 >= end
                || UNSAFE.getLong(bytes, BASE + offset - 10) != name1
                || UNSAFE.getShort(bytes, BASE + offset - 2) != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match14(long name1, int name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 15;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 11) != name1
                || (UNSAFE.getInt(bytes, BASE + offset - 3) & 0xFFFFFF) != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match15(long name1, int name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 16;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 12) != name1
                || UNSAFE.getInt(bytes, BASE + offset - 4) != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match16(long name1, int name2, byte name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 17;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 13) != name1
                || UNSAFE.getInt(bytes, BASE + offset - 5) != name2
                || bytes[offset - 1] != name3) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match17(long name1, long name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 18;
        if (offset + 1 >= end
                || UNSAFE.getLong(bytes, BASE + offset - 14) != name1
                || (UNSAFE.getLong(bytes, BASE + offset - 6) & 0xFFFFFFFFFFFFL) != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match18(long name1, long name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 19;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 15) != name1
                || (UNSAFE.getLong(bytes, BASE + offset - 7) & 0xFFFF_FFFF_FFFF_FFL) != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match19(long name1, long name2) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 20;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 16) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 8) != name2) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match20(long name1, long name2, byte name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 21;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 17) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 9) != name2
                || bytes[offset - 1] != name3
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match21(long name1, long name2, int name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 22;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 18) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 10) != name2
                || UNSAFE.getShort(bytes, BASE + offset - 2) != name3
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match22(long name1, long name2, int name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 23;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 19) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 11) != name2
                || (UNSAFE.getInt(bytes, BASE + offset - 3) & 0xFFFFFF) != name3
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match23(long name1, long name2, int name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 24;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 20) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 12) != name2
                || UNSAFE.getInt(bytes, BASE + offset - 4) != name3
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    public boolean nextIfName4Match24(long name1, long name2, int name3, byte name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 25;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 21) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 13) != name2
                || UNSAFE.getInt(bytes, BASE + offset - 5) != name3
                || bytes[offset - 1] != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match25(long name1, long name2, long name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 26;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 22) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 14) != name2
                || (UNSAFE.getLong(bytes, BASE + offset - 6) & 0xFFFF_FFFF_FFFFL) != name3
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match26(long name1, long name2, long name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 27;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 23) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 15) != name2
                || (UNSAFE.getLong(bytes, BASE + offset - 7) & 0xFFFF_FFFF_FFFF_FFL) != name3
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match27(long name1, long name2, long name3) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 28;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 24) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 16) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 8) != name3
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match28(long name1, long name2, long name3, byte name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 29;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 25) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 17) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 9) != name3
                || bytes[offset - 1] != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match29(long name1, long name2, long name3, int name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 30;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 26) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 18) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 10) != name3
                || UNSAFE.getShort(bytes, BASE + offset - 2) != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match30(long name1, long name2, long name3, int name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 31;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 27) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 19) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 11) != name3
                || (UNSAFE.getInt(bytes, BASE + offset - 3) & 0xFFFFFF) != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match31(long name1, long name2, long name3, int name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 32;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 28) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 20) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 12) != name3
                || UNSAFE.getInt(bytes, BASE + offset - 4) != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match32(long name1, long name2, long name3, int name4, byte name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 33;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 29) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 21) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 13) != name3
                || UNSAFE.getInt(bytes, BASE + offset - 5) != name4
                || bytes[offset - 1] != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match33(long name1, long name2, long name3, long name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 34;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 30) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 22) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 14) != name3
                || (UNSAFE.getLong(bytes, BASE + offset - 6) & 0xFFFF_FFFF_FFFFL) != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match34(long name1, long name2, long name3, long name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 35;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 31) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 23) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 15) != name3
                || (UNSAFE.getLong(bytes, BASE + offset - 7) & 0xFFFF_FFFF_FFFF_FFL) != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match35(long name1, long name2, long name3, long name4) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 36;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 32) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 24) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 16) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 8) != name4
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match36(long name1, long name2, long name3, long name4, byte name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 37;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 33) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 25) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 17) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 9) != name4
                || bytes[offset - 1] != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match37(long name1, long name2, long name3, long name4, int name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 38;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 34) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 26) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 18) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 10) != name4
                || UNSAFE.getShort(bytes, BASE + offset - 2) != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match38(long name1, long name2, long name3, long name4, int name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 39;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 35) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 27) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 19) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 11) != name4
                || (UNSAFE.getInt(bytes, BASE + offset - 3) & 0xFFFFFF) != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match39(long name1, long name2, long name3, long name4, int name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 40;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 36) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 28) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 20) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 12) != name4
                || UNSAFE.getInt(bytes, BASE + offset - 4) != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match40(long name1, long name2, long name3, long name4, int name5, byte name6) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 41;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 37) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 29) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 21) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 13) != name4
                || UNSAFE.getInt(bytes, BASE + offset - 5) != name5
                || bytes[offset - 1] != name6
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match41(long name1, long name2, long name3, long name4, long name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 42;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 38) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 30) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 22) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 14) != name4
                || (UNSAFE.getLong(bytes, BASE + offset - 6) & 0xFFFF_FFFF_FFFFL) != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match42(long name1, long name2, long name3, long name4, long name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 43;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 39) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 31) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 23) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 15) != name4
                || (UNSAFE.getLong(bytes, BASE + offset - 7) & 0xFFFF_FFFF_FFFF_FFL) != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    @Override
    public boolean nextIfName4Match43(long name1, long name2, long name3, long name4, long name5) {
        byte[] bytes = this.bytes;
        int offset = this.offset + 44;
        if (offset >= end
                || UNSAFE.getLong(bytes, BASE + offset - 40) != name1
                || UNSAFE.getLong(bytes, BASE + offset - 32) != name2
                || UNSAFE.getLong(bytes, BASE + offset - 24) != name3
                || UNSAFE.getLong(bytes, BASE + offset - 16) != name4
                || UNSAFE.getLong(bytes, BASE + offset - 8) != name5
        ) {
            return false;
        }

        this.offset = offset;
        return true;
    }

    static int getInt(byte[] bytes, int offset) {
        int int32Value = UNSAFE.getInt(bytes, ARRAY_BYTE_BASE_OFFSET + offset);
        return BIG_ENDIAN ? int32Value : Integer.reverseBytes(int32Value);
    }
}
