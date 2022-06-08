package com.alibaba.fastjson;

import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.*;
import com.alibaba.fastjson.util.IOUtils;
import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.filter.Filter;
import com.alibaba.fastjson2.filter.PropertyFilter;
import com.alibaba.fastjson2.filter.PropertyPreFilter;
import com.alibaba.fastjson2.filter.ValueFilter;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import com.alibaba.fastjson2.support.AwtRederModule;
import com.alibaba.fastjson2.support.AwtWriterModule;
import com.alibaba.fastjson2.util.ParameterizedTypeImpl;
import com.alibaba.fastjson2.writer.ObjectWriter;
import com.alibaba.fastjson2.writer.ObjectWriterProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

public class JSON {
    private static TimeZone DEFAULT_TIME_ZONE = TimeZone.getDefault();
    public static final String VERSION = com.alibaba.fastjson2.JSON.VERSION;
    static final Cache CACHE = new Cache();
    static final AtomicReferenceFieldUpdater<Cache, char[]> CHARS_UPDATER
            = AtomicReferenceFieldUpdater.newUpdater(Cache.class, char[].class, "chars");
    public static TimeZone defaultTimeZone = DEFAULT_TIME_ZONE;
    public static Locale defaultLocale = Locale.getDefault();
    public static String DEFAULT_TYPE_KEY = "@type";
    public static String DEFFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static int DEFAULT_PARSER_FEATURE;
    public static int DEFAULT_GENERATE_FEATURE;

    static final Supplier<List> arraySupplier = JSONArray::new;
    static final Supplier<Map> defaultSupplier = JSONObject::new;
    static final Supplier<Map> orderedSupplier = () -> new JSONObject(true);

    static {
        ObjectReaderProvider readerProvider = JSONFactory.getDefaultObjectReaderProvider();
        readerProvider.register(AwtRederModule.INSTANCE);
        readerProvider.register(new Fastjson1xReaderModule(readerProvider));

        ObjectWriterProvider writerProvider = JSONFactory.getDefaultObjectWriterProvider();
        writerProvider.register(AwtWriterModule.INSTANCE);
        writerProvider.register(new Fastjson1xWriterModule(writerProvider));
    }

    public static JSONObject parseObject(String str) {
        return parseObject(str, JSONObject.class);
    }

    public static JSONObject parseObject(String text, Feature... features) {
        return parseObject(text, JSONObject.class, features);
    }

    public static <T> T parseObject(char[] str, Class type, Feature... features) {
        return parseObject(new String(str), type, features);
    }

    public static <T> T parseObject(String str, TypeReference typeReference, Feature... features) {
        return parseObject(str, typeReference.getType(), features);
    }

    public static <T> T parseObject(String text, Class<T> clazz) {
        return parseObject(text, clazz, new Feature[0]);
    }

    public static <T> T parseObject(String str, Type type, Feature... features) {
        if (str == null || str.isEmpty()) {
            return null;
        }

        JSONReader reader = JSONReader.of(str);
        JSONReader.Context context = reader.getContext();
        context.setArraySupplier(arraySupplier);
        context.setObjectSupplier(defaultSupplier);

        String defaultDateFormat = JSON.DEFFAULT_DATE_FORMAT;
        if (!"yyyy-MM-dd HH:mm:ss".equals(defaultDateFormat)) {
            context.setUtilDateFormat(defaultDateFormat);
        }

        config(context, features);

        try {
            T object = reader.read(type);
            if (object != null) {
                reader.handleResolveTasks(object);
            }
            return object;
        } catch (com.alibaba.fastjson2.JSONException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            throw new JSONException(e.getMessage(), cause);
        }
    }

    public static <T> T parseObject(byte[] jsonBytes, Type type, Feature... features) {
        if (jsonBytes == null) {
            return null;
        }

        JSONReader reader = JSONReader.of(jsonBytes);
        JSONReader.Context context = reader.getContext();
        context.setObjectSupplier(defaultSupplier);
        context.setArraySupplier(arraySupplier);

        String defaultDateFormat = JSON.DEFFAULT_DATE_FORMAT;
        if (!"yyyy-MM-dd HH:mm:ss".equals(defaultDateFormat)) {
            context.setUtilDateFormat(defaultDateFormat);
        }

        config(context, features);
        try {
            return reader.read(type);
        } catch (com.alibaba.fastjson2.JSONException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            throw new JSONException(e.getMessage(), cause);
        }
    }

    public static <T> T parseObject(byte[] jsonBytes, Type type, SerializeFilter filter, Feature... features) {
        if (jsonBytes == null) {
            return null;
        }

        JSONReader reader = JSONReader.of(jsonBytes);
        JSONReader.Context context = reader.getContext();
        context.setObjectSupplier(defaultSupplier);
        context.setArraySupplier(arraySupplier);

        String defaultDateFormat = JSON.DEFFAULT_DATE_FORMAT;
        if (!"yyyy-MM-dd HH:mm:ss".equals(defaultDateFormat)) {
            context.setUtilDateFormat(defaultDateFormat);
        }

        if (filter instanceof Filter) {
            context.config((Filter) filter);
        }

        config(context, features);
        try {
            return reader.read(type);
        } catch (com.alibaba.fastjson2.JSONException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            throw new JSONException(e.getMessage(), cause);
        }
    }

    public static Object parse(String str, Feature... features) {
        if (str == null || str.isEmpty()) {
            return null;
        }

        try (JSONReader reader = JSONReader.of(str)) {
            JSONReader.Context context = reader.getContext();
            context.setObjectSupplier(defaultSupplier);
            context.setArraySupplier(arraySupplier);
            config(context, features);
            if (reader.isObject() && !reader.isSupportAutoType(0)) {
                return reader.read(JSONObject.class);
            }
            return reader.readAny();
        } catch (Exception ex) {
            throw new JSONException(ex.getMessage(), ex);
        }
    }

    protected static void config(JSONReader.Context context, Feature[] features) {
        for (Feature feature : features) {
            switch (feature) {
                case SupportArrayToBean:
                    context.config(JSONReader.Feature.SupportArrayToBean);
                    break;
                case SupportAutoType:
                    context.config(JSONReader.Feature.SupportAutoType);
                    break;
                case ErrorOnEnumNotMatch:
                    context.config(JSONReader.Feature.ErrorOnEnumNotMatch);
                    break;
                case SupportNonPublicField:
                    context.config(JSONReader.Feature.FieldBased);
                    break;
                case SupportClassForName:
                    context.config(JSONReader.Feature.SupportClassForName);
                    break;
                case TrimStringFieldValue:
                    context.config(JSONReader.Feature.TrimString);
                    break;
                case ErrorOnNotSupportAutoType:
                    context.config(JSONReader.Feature.ErrorOnNotSupportAutoType);
                    break;
                case AllowUnQuotedFieldNames:
                    context.config(JSONReader.Feature.AllowUnQuotedFieldNames);
                    break;
                case OrderedField:
                    context.setObjectSupplier(orderedSupplier);
                    break;
                default:
                    break;
            }
        }

        boolean disableFieldSmartMatch = false;
        for (Feature feature : features) {
            if (feature.equals(Feature.DisableFieldSmartMatch)) {
                disableFieldSmartMatch = true;
                break;
            }
        }

        if (!disableFieldSmartMatch) {
            context.config(JSONReader.Feature.SupportSmartMatch);
        }
    }

    public static Object parse(byte[] input, int off, int len, CharsetDecoder charsetDecoder, Feature... features) {
        if (input == null || input.length == 0) {
            return null;
        }

        int featureValues = DEFAULT_PARSER_FEATURE;
        for (Feature feature : features) {
            featureValues = Feature.config(featureValues, feature, true);
        }

        return parse(input, off, len, charsetDecoder, featureValues);
    }

    public static Object parse(byte[] input, int off, int len, CharsetDecoder charsetDecoder, int features) {
        charsetDecoder.reset();

        int scaleLength = (int) (len * (double) charsetDecoder.maxCharsPerByte());
        char[] chars = CHARS_UPDATER.getAndSet(CACHE, null);
        if (chars == null || chars.length < scaleLength) {
            chars = new char[scaleLength];
        }

        try {
            ByteBuffer byteBuf = ByteBuffer.wrap(input, off, len);
            CharBuffer charBuf = CharBuffer.wrap(chars);
            IOUtils.decode(charsetDecoder, byteBuf, charBuf);

            int position = charBuf.position();

            JSONReader reader = JSONReader.of(chars, 0, position);
            JSONReader.Context context = reader.getContext();

            for (Feature feature : Feature.values()) {
                if ((features & feature.mask) != 0) {
                    switch (feature) {
                        case SupportArrayToBean:
                            context.config(JSONReader.Feature.SupportArrayToBean);
                            break;
                        case SupportAutoType:
                            context.config(JSONReader.Feature.SupportAutoType);
                            break;
                        case ErrorOnEnumNotMatch:
                            context.config(JSONReader.Feature.ErrorOnEnumNotMatch);
                        case SupportNonPublicField:
                            context.config(JSONReader.Feature.FieldBased);
                        default:
                            break;
                    }
                }
            }

            return reader.read(Object.class);
        } finally {
            if (chars.length <= 1024 * 64) {
                CHARS_UPDATER.set(CACHE, chars);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T parseObject(byte[] input, //
                                    int off, //
                                    int len, //
                                    CharsetDecoder charsetDecoder, //
                                    Type clazz, //
                                    Feature... features) {
        charsetDecoder.reset();

        int scaleLength = (int) (len * (double) charsetDecoder.maxCharsPerByte());

        char[] chars = CHARS_UPDATER.getAndSet(CACHE, null);
        if (chars == null || chars.length < scaleLength) {
            chars = new char[scaleLength];
        }

        try {
            ByteBuffer byteBuf = ByteBuffer.wrap(input, off, len);
            CharBuffer charByte = CharBuffer.wrap(chars);
            IOUtils.decode(charsetDecoder, byteBuf, charByte);

            int position = charByte.position();

            JSONReader reader = JSONReader.of(chars, 0, position);
            config(reader.getContext(), features);
            return reader.read(clazz);
        } finally {
            if (chars.length <= 1024 * 64) {
                CHARS_UPDATER.set(CACHE, chars);
            }
        }
    }

    public static <T> T parseObject(
            byte[] input,
            int off,
            int len,
            Charset charset,
            Type clazz,
            Feature... features) {
        try (JSONReader reader = JSONReader.of(input, off, len, charset)) {
            config(reader.getContext(), features);
            return reader.read(clazz);
        }
    }

    public static String toJSONString(Object object, SerializeFilter[] filters, SerializerFeature... features) {
        JSONWriter.Context context = JSONFactory.createWriteContext();
        config(context, features);

        try (JSONWriter writer = JSONWriter.of(context)) {
            writer.setRootObject(object);
            configFilter(context, filters);

            if (object == null) {
                writer.writeNull();
            } else {
                Class<?> valueClass = object.getClass();
                ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
                objectWriter.write(writer, object, null, null, 0);
            }

            return writer.toString();
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("toJSONString error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("toJSONString error", ex);
        }
    }

    public static void configFilter(JSONWriter.Context context, SerializeFilter... filters) {
        for (SerializeFilter filter : filters) {
            if (filter instanceof NameFilter) {
                context.setNameFilter((NameFilter) filter);
            } else if (filter instanceof ValueFilter) {
                context.setValueFilter((ValueFilter) filter);
            } else if (filter instanceof PropertyPreFilter) {
                context.setPropertyPreFilter((PropertyPreFilter) filter);
            } else if (filter instanceof PropertyFilter) {
                context.setPropertyFilter((PropertyFilter) filter);
            } else if (filter instanceof BeforeFilter) {
                context.setBeforeFilter((BeforeFilter) filter);
            } else if (filter instanceof AfterFilter) {
                context.setAfterFilter((AfterFilter) filter);
            } else if (filter instanceof LabelFilter) {
                context.setLabelFilter((LabelFilter) filter);
            }
        }
    }

    public static byte[] toJSONBytes(Object object, SerializeFilter[] filters, SerializerFeature... features) {
        JSONWriter.Context context = JSONFactory.createWriteContext();
        config(context, features);

        try (JSONWriter writer = JSONWriter.ofUTF8(context)) {
            writer.setRootObject(object);
            configFilter(context, filters);

            if (object == null) {
                writer.writeNull();
            } else {
                Class<?> valueClass = object.getClass();
                ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
                objectWriter.write(writer, object, null, null, 0);
            }

            return writer.getBytes();
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("toJSONBytes error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("toJSONBytes error", ex);
        }
    }

    public static String toJSONString(Object object, boolean prettyFormat) {
        JSONWriter.Context context = JSONFactory.createWriteContext(JSONWriter.Feature.ReferenceDetection);
        if (prettyFormat) {
            context.config(JSONWriter.Feature.PrettyFormat);
        }
        context.setDateFormat("millis");
        try (JSONWriter writer = JSONWriter.of(context)) {
            writer.setRootObject(object);

            if (object == null) {
                writer.writeNull();
            } else {
                Class<?> valueClass = object.getClass();
                ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
                objectWriter.write(writer, object, null, null, 0);
            }

            return writer.toString();
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("toJSONString error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("toJSONString error", ex);
        }
    }

    public static String toJSONString(Object object) {
        JSONWriter.Context context = JSONFactory.createWriteContext(JSONWriter.Feature.ReferenceDetection);
        context.setDateFormat("millis");
        try (JSONWriter writer = JSONWriter.of(context)) {
            writer.setRootObject(object);

            if (object == null) {
                writer.writeNull();
            } else {
                Class<?> valueClass = object.getClass();
                ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
                objectWriter.write(writer, object, null, null, 0);
            }

            return writer.toString();
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("toJSONString error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("toJSONString error", ex);
        }
    }

    public static String toJSONString(Object object, SerializeFilter... filters) {
        return toJSONString(object, filters, new SerializerFeature[0]);
    }

    public static String toJSONString(Object object, SerializerFeature... features) {
        JSONWriter.Context context = JSONFactory.createWriteContext();
        context.setDateFormat("millis");
        config(context, features);

        try (JSONWriter writer = JSONWriter.of(context)) {
            writer.setRootObject(object);

            if (object == null) {
                writer.writeNull();
            } else {
                Class<?> valueClass = object.getClass();
                ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
                objectWriter.write(writer, object, null, null, 0);
            }

            return writer.toString();
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("toJSONString error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("toJSONString error", ex);
        }
    }

    public static byte[] toJSONBytes(Object object) {
        JSONWriter.Context context = JSONFactory.createWriteContext(JSONWriter.Feature.ReferenceDetection);
        context.setDateFormat("millis");
        try (JSONWriter writer = JSONWriter.ofUTF8(context)) {
            writer.setRootObject(object);

            if (object == null) {
                writer.writeNull();
            } else {
                Class<?> valueClass = object.getClass();
                ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
                objectWriter.write(writer, object, null, null, 0);
            }

            return writer.getBytes();
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("toJSONBytes error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("toJSONBytes error", ex);
        }
    }

    public static byte[] toJSONBytes(Object object, SerializeFilter... filters) {
        return toJSONBytes(object, filters, new SerializerFeature[0]);
    }

    public static byte[] toJSONBytes(Object object, SerializerFeature... features) {
        JSONWriter.Context context = JSONFactory.createWriteContext();
        config(context, features);

        try (JSONWriter writer = JSONWriter.ofUTF8(context)) {
            writer.setRootObject(object);

            if (object == null) {
                writer.writeNull();
            } else {
                Class<?> valueClass = object.getClass();
                ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
                objectWriter.write(writer, object, null, null, 0);
            }

            return writer.getBytes();
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("toJSONBytes error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("toJSONBytes error", ex);
        }
    }

    public static void config(JSONWriter.Context ctx, SerializerFeature[] features) {
        ctx.setDateFormat("millis");
        if (defaultTimeZone != null && defaultTimeZone != DEFAULT_TIME_ZONE) {
            ctx.setZoneId(defaultTimeZone.toZoneId());
        }

        boolean disableCircularReferenceDetect = false;
        for (SerializerFeature feature : features) {
            if (feature == SerializerFeature.DisableCircularReferenceDetect) {
                disableCircularReferenceDetect = true;
                break;
            }
        }
        if (!disableCircularReferenceDetect) {
            ctx.config(JSONWriter.Feature.ReferenceDetection);
        }

        for (SerializerFeature feature : features) {
            switch (feature) {
                case UseISO8601DateFormat:
                    ctx.setDateFormat("iso8601");
                    break;
                case WriteMapNullValue:
                    ctx.config(JSONWriter.Feature.WriteNulls);
                    break;
                case WriteNullListAsEmpty:
                    ctx.config(JSONWriter.Feature.WriteNullListAsEmpty);
                    break;
                case WriteNullStringAsEmpty:
                    ctx.config(JSONWriter.Feature.WriteNullStringAsEmpty);
                    break;
                case WriteNullNumberAsZero:
                    ctx.config(JSONWriter.Feature.WriteNullNumberAsZero);
                    break;
                case WriteNullBooleanAsFalse:
                    ctx.config(JSONWriter.Feature.WriteNullBooleanAsFalse);
                    break;
                case BrowserCompatible:
                    ctx.config(JSONWriter.Feature.BrowserCompatible);
                    break;
                case WriteClassName:
                    ctx.config(JSONWriter.Feature.WriteClassName);
                    break;
                case WriteNonStringValueAsString:
                    ctx.config(JSONWriter.Feature.WriteNonStringValueAsString);
                    break;
                case WriteEnumUsingToString:
                    ctx.config(JSONWriter.Feature.WriteEnumUsingToString);
                    break;
                case NotWriteRootClassName:
                    ctx.config(JSONWriter.Feature.NotWriteRootClassName);
                    break;
                case IgnoreErrorGetter:
                    ctx.config(JSONWriter.Feature.IgnoreErrorGetter);
                    break;
                case WriteDateUseDateFormat:
                    ctx.setDateFormat(JSON.DEFFAULT_DATE_FORMAT);
                    break;
                case BeanToArray:
                    ctx.config(JSONWriter.Feature.BeanToArray);
                    break;
                case UseSingleQuotes:
                    ctx.config(JSONWriter.Feature.UseSingleQuotes);
                    break;
                case MapSortField:
                    ctx.config(JSONWriter.Feature.MapSortField);
                    break;
                case PrettyFormat:
                    ctx.config(JSONWriter.Feature.PrettyFormat);
                    break;
                case WriteNonStringKeyAsString:
                    ctx.config(JSONWriter.Feature.WriteNonStringKeyAsString);
                    break;
                default:
                    break;
            }
        }
    }

    public static String toJSONString(Object object, SerializeConfig config, SerializerFeature... features) {
        return toJSONString(object, DEFAULT_GENERATE_FEATURE, features);
    }

    public static String toJSONString(Object object, int defaultFeatures, SerializerFeature... features) {
        try (JSONWriter writer = JSONWriter.of()) {
            JSONWriter.Context ctx = writer.getContext();

            config(ctx, features);

            writer.writeAny(object);
            return writer.toString();
        }
    }

    public static String toJSONStringWithDateFormat(Object object, String dateFormat,
                                                    SerializerFeature... features) {
        //return toJSONString(object, SerializeConfig.globalInstance, null, dateFormat, DEFAULT_GENERATE_FEATURE, features);
        try (JSONWriter writer = JSONWriter.of()) {
            for (SerializerFeature feature : features) {
                if (feature == SerializerFeature.WriteMapNullValue) {
                    writer.config(JSONWriter.Feature.WriteNulls);
                }
            }

            writer.getContext().setDateFormat(dateFormat);
            writer.writeAny(object);
            return writer.toString();
        }
    }

    public static final int writeJSONString(OutputStream os, //
                                            Object object, //
                                            SerializerFeature... features) throws IOException {
        return writeJSONString(os, object, new SerializeFilter[0], features);
    }

    public static final int writeJSONString(OutputStream os, //
                                            Object object, //
                                            SerializeFilter[] filters) throws IOException {
        return writeJSONString(os, object, filters, new SerializerFeature[0]);
    }

    public static final int writeJSONString(OutputStream os, //
                                            Object object, //
                                            SerializeFilter[] filters, //
                                            SerializerFeature... features) throws IOException {
        try (JSONWriter writer = JSONWriter.ofUTF8()) {
            JSONWriter.Context context = writer.getContext();
            writer.setRootObject(object);
            config(context, features);
            configFilter(context, filters);

            writer.writeAny(object);
            byte[] bytes = writer.getBytes();
            os.write(bytes);
            return bytes.length;
        } catch (com.alibaba.fastjson2.JSONException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new JSONException("writeJSONString error", cause);
        } catch (RuntimeException ex) {
            throw new JSONException("writeJSONString error", ex);
        }
    }

    public static JSONArray parseArray(String str, Feature... features) {
        try (JSONReader reader = JSONReader.of(str)) {
            JSONReader.Context context = reader.getContext();
            context.setObjectSupplier(defaultSupplier);
            context.setArraySupplier(arraySupplier);
            config(context, features);
            ObjectReader<JSONArray> objectReader = reader.getObjectReader(JSONArray.class);
            return objectReader.readObject(reader, 0);
        }
    }

    public static <T> List<T> parseArray(String text, Class<T> type) {
        if (text == null || text.length() == 0) {
            return null;
        }
        ParameterizedTypeImpl paramType = new ParameterizedTypeImpl(new Type[]{type}, null, List.class);

        try (JSONReader reader = JSONReader.of(text)) {
            return reader.read(paramType);
        } catch (com.alibaba.fastjson2.JSONException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            throw new JSONException(e.getMessage(), cause);
        }
    }

    public static <T> List<T> parseArray(String text, Class<T> type, Feature... features) {
        if (text == null || text.length() == 0) {
            return null;
        }
        ParameterizedTypeImpl paramType = new ParameterizedTypeImpl(new Type[]{type}, null, List.class);

        try (JSONReader reader = JSONReader.of(text)) {
            config(reader.getContext(), features);

            return reader.read(paramType);
        } catch (com.alibaba.fastjson2.JSONException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            throw new JSONException(e.getMessage(), cause);
        }
    }

    public static boolean isValid(String str) {
        return com.alibaba.fastjson2.JSON.isValid(str);
    }

    public static boolean isValidArray(String str) {
        return com.alibaba.fastjson2.JSON.isValidArray(str);
    }

    public static <T> T toJavaObject(JSON json, Class<T> clazz) {
        if (json instanceof JSONObject) {
            return ((JSONObject) json).toJavaObject(clazz);
        }

        String str = toJSONString(json);
        return parseObject(str, clazz);
    }

    public static Object toJSON(Object javaObject) {
        if (javaObject instanceof JSON) {
            return javaObject;
        }

        String str = JSON.toJSONString(javaObject);
        Object object = JSON.parse(str);
        if (object instanceof List) {
            return new JSONArray((List) object);
        }
        return object;
    }

    public static List<Object> parseArray(String text, Type[] types) {
        return com.alibaba.fastjson2.JSON.parseArray(text, types);
    }

    static class Cache {
        volatile char[] chars;
    }
}
