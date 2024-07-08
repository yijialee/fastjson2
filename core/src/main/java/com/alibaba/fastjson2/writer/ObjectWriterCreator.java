package com.alibaba.fastjson2.writer;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.codec.BeanInfo;
import com.alibaba.fastjson2.codec.FieldInfo;
import com.alibaba.fastjson2.filter.*;
import com.alibaba.fastjson2.function.*;
import com.alibaba.fastjson2.util.BeanUtils;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static com.alibaba.fastjson2.JSONWriter.Feature.WriteClassName;

public class ObjectWriterCreator {
    public static final ObjectWriterCreator INSTANCE = new ObjectWriterCreator();

    public ObjectWriterCreator() {
    }

    public ObjectWriter createObjectWriter(List<FieldWriter> fieldWriters) {
        return new ObjectWriterAdapter(null, null, null, 0, fieldWriters);
    }

    public ObjectWriter createObjectWriter(FieldWriter... fieldWriters) {
        return new ObjectWriterAdapter(null, null, null, 0, Arrays.asList(fieldWriters));
    }

    public ObjectWriter createObjectWriter(Class objectType) {
        return createObjectWriter(
                objectType,
                0,
                JSONFactory.defaultObjectWriterProvider
        );
    }

    public ObjectWriter createObjectWriter(Class objectType,
                                           FieldWriter... fieldWriters) {
        return createObjectWriter(objectType, 0, fieldWriters);
    }

    public ObjectWriter createObjectWriter(
            Class objectClass,
            long features,
            FieldWriter... fieldWriters
    ) {
        if (fieldWriters.length == 0) {
            return createObjectWriter(objectClass, features, JSONFactory.defaultObjectWriterProvider);
        }

        boolean googleCollection = false;
        if (objectClass != null) {
            String typeName = objectClass.getName();
            googleCollection =
                    "com.google.common.collect.AbstractMapBasedMultimap$RandomAccessWrappedList".equals(typeName)
                            || "com.google.common.collect.AbstractMapBasedMultimap$WrappedSet".equals(typeName);
        }

        if (!googleCollection) {
            switch (fieldWriters.length) {
                case 1:
                    if ((fieldWriters[0].features & FieldInfo.VALUE_MASK) == 0) {
                        return new ObjectWriter1(objectClass, null, null, features, Arrays.asList(fieldWriters));
                    }
                    return new ObjectWriterAdapter(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 2:
                    return new ObjectWriter2(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 3:
                    return new ObjectWriter3(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 4:
                    return new ObjectWriter4(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 5:
                    return new ObjectWriter5(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 6:
                    return new ObjectWriter6(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 7:
                    return new ObjectWriter7(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 8:
                    return new ObjectWriter8(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 9:
                    return new ObjectWriter9(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 10:
                    return new ObjectWriter10(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 11:
                    return new ObjectWriter11(objectClass, null, null, features, Arrays.asList(fieldWriters));
                case 12:
                    return new ObjectWriter12(objectClass, null, null, features, Arrays.asList(fieldWriters));
                default:
                    return new ObjectWriterAdapter(objectClass, null, null, features, Arrays.asList(fieldWriters));
            }
        }

        return new ObjectWriterAdapter(objectClass, null, null, features, Arrays.asList(fieldWriters));
    }

    protected FieldWriter creteFieldWriter(
            Class objectClass,
            long writerFeatures,
            ObjectWriterProvider provider,
            BeanInfo beanInfo,
            FieldInfo fieldInfo,
            Field field
    ) {
        fieldInfo.features = writerFeatures;
        provider.getFieldInfo(beanInfo, fieldInfo, objectClass, field);

        if (fieldInfo.ignore) {
            return null;
        }

        String fieldName;
        if (fieldInfo.fieldName == null || fieldInfo.fieldName.isEmpty()) {
            fieldName = field.getName();

            if (beanInfo.namingStrategy != null) {
                fieldName = BeanUtils.fieldName(fieldName, beanInfo.namingStrategy);
            }
        } else {
            fieldName = fieldInfo.fieldName;
        }

        if (beanInfo.orders != null) {
            boolean match = false;
            for (int i = 0; i < beanInfo.orders.length; i++) {
                if (fieldName.equals(beanInfo.orders[i])) {
                    fieldInfo.ordinal = i;
                    match = true;
                }
            }
            if (!match) {
                if (fieldInfo.ordinal == 0) {
                    fieldInfo.ordinal = beanInfo.orders.length;
                }
            }
        }

        String[] includes = beanInfo.includes;
        if (includes != null && includes.length > 0) {
            boolean match = false;
            for (int i = 0; i < includes.length; i++) {
                String include = includes[i];
                if (include.equals(fieldName)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                return null;
            }
        }

        ObjectWriter writeUsingWriter = null;
        if (fieldInfo.writeUsing != null) {
            try {
                Constructor<?> constructor = fieldInfo.writeUsing.getDeclaredConstructor();
                constructor.setAccessible(true);
                writeUsingWriter = (ObjectWriter) constructor.newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException e) {
                throw new JSONException("create writeUsing Writer error", e);
            }
        }

        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
            // ignored
        }

        if (writeUsingWriter == null && fieldInfo.fieldClassMixIn) {
            writeUsingWriter = ObjectWriterProvider.VoidObjectWriter.INSTANCE;
        }

        if (writeUsingWriter == null) {
            Class<?> fieldClass = field.getType();
            if (fieldClass == Date.class) {
                ObjectWriter objectWriter = provider.cache.get(fieldClass);
                if (objectWriter != ObjectWriterImplDate.INSTANCE) {
                    writeUsingWriter = objectWriter;
                }
            } else if (Map.class.isAssignableFrom(fieldClass)
                    && (fieldInfo.keyUsing != null || fieldInfo.valueUsing != null)) {
                ObjectWriter keyWriter = null;
                ObjectWriter valueWriter = null;
                if (fieldInfo.keyUsing != null) {
                    try {
                        Constructor<?> constructor = fieldInfo.keyUsing.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        keyWriter = (ObjectWriter) constructor.newInstance();
                    } catch (Exception ignored) {
                        // ignored
                    }
                }
                if (fieldInfo.valueUsing != null) {
                    try {
                        Constructor<?> constructor = fieldInfo.valueUsing.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        valueWriter = (ObjectWriter) constructor.newInstance();
                    } catch (Exception ignored) {
                        // ignored
                    }
                }

                if (keyWriter != null || valueWriter != null) {
                    ObjectWriterImplMap mapWriter = ObjectWriterImplMap.of(field.getType(), fieldClass);
                    mapWriter.keyWriter = keyWriter;
                    mapWriter.valueWriter = valueWriter;
                    writeUsingWriter = mapWriter;
                }
            }
        }

        String format = fieldInfo.format;
        if (format == null && beanInfo.format != null) {
            format = beanInfo.format;
        }

        return createFieldWriter(provider, fieldName, fieldInfo.ordinal, fieldInfo.features, format, fieldInfo.label, field, writeUsingWriter);
    }

    public ObjectWriter createObjectWriter(
            final Class objectClass,
            final long features,
            final ObjectWriterProvider provider
    ) {
        BeanInfo beanInfo = new BeanInfo();

        provider.getBeanInfo(beanInfo, objectClass);

        if (beanInfo.serializer != null && ObjectWriter.class.isAssignableFrom(beanInfo.serializer)) {
            try {
                return (ObjectWriter) beanInfo.serializer.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                throw new JSONException("create serializer error", e);
            }
        }

        long beanFeatures = beanInfo.writerFeatures;
        if (beanInfo.seeAlso != null) {
            beanFeatures &= ~JSONWriter.Feature.WriteClassName.mask;
        }

        long writerFieldFeatures = (features | beanFeatures) & ~JSONWriter.Feature.BeanToArray.mask;
        boolean fieldBased = (writerFieldFeatures & JSONWriter.Feature.FieldBased.mask) != 0;

        if (fieldBased && objectClass.isInterface()) {
            fieldBased = false;
        }

        List<FieldWriter> fieldWriters;
        final FieldInfo fieldInfo = new FieldInfo();

        if (fieldBased) {
            Map<String, FieldWriter> fieldWriterMap = new TreeMap<>();
            BeanUtils.declaredFields(objectClass, field -> {
                fieldInfo.init();
                FieldWriter fieldWriter = creteFieldWriter(objectClass, writerFieldFeatures, provider, beanInfo, fieldInfo, field);
                if (fieldWriter != null) {
                    fieldWriterMap.put(fieldWriter.fieldName, fieldWriter);
                }
            });
            fieldWriters = new ArrayList<>(fieldWriterMap.values());
        } else {
            {
                Map<String, FieldWriter> fieldWriterMap = new TreeMap<>();

                BeanUtils.declaredFields(objectClass, field -> {
                    fieldInfo.init();
                    fieldInfo.ignore = (field.getModifiers() & Modifier.PUBLIC) == 0;
                    FieldWriter fieldWriter = creteFieldWriter(objectClass, writerFieldFeatures, provider, beanInfo, fieldInfo, field);
                    if (fieldWriter != null) {
                        FieldWriter origin = fieldWriterMap.get(fieldWriter.fieldName);
                        if (origin == null) {
                            fieldWriterMap.put(fieldWriter.fieldName, fieldWriter);
                        }

                        if (origin != null && origin.compareTo(fieldWriter) > 0) {
                            fieldWriterMap.put(fieldWriter.fieldName, fieldWriter);
                        }
                    }
                });

                Class mixIn = provider.getMixIn(objectClass);

                BeanUtils.getters(objectClass, mixIn, beanInfo.kotlin, method -> {
                    fieldInfo.init();
                    fieldInfo.features = writerFieldFeatures;
                    fieldInfo.format = beanInfo.format;
                    provider.getFieldInfo(beanInfo, fieldInfo, objectClass, method);
                    if (fieldInfo.ignore) {
                        return;
                    }

                    String fieldName = getFieldName(objectClass, beanInfo, fieldInfo, method);

                    String[] includes = beanInfo.includes;
                    if (includes != null && includes.length > 0) {
                        boolean match = false;
                        for (int i = 0; i < includes.length; i++) {
                            String include = includes[i];
                            if (include.equals(fieldName)) {
                                match = true;
                                break;
                            }
                        }
                        if (!match) {
                            return;
                        }
                    }

                    // skip typeKey field
                    if ((beanInfo.writerFeatures & WriteClassName.mask) != 0
                            && fieldName.equals(beanInfo.typeKey)) {
                        return;
                    }

                    if (beanInfo.orders != null) {
                        boolean match = false;
                        for (int i = 0; i < beanInfo.orders.length; i++) {
                            if (fieldName.equals(beanInfo.orders[i])) {
                                fieldInfo.ordinal = i;
                                match = true;
                            }
                        }
                        if (!match) {
                            if (fieldInfo.ordinal == 0) {
                                fieldInfo.ordinal = beanInfo.orders.length;
                            }
                        }
                    }

                    ObjectWriter writeUsingWriter = null;
                    if (fieldInfo.writeUsing != null) {
                        try {
                            Constructor<?> constructor = fieldInfo.writeUsing.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            writeUsingWriter = (ObjectWriter) constructor.newInstance();
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                                 NoSuchMethodException e) {
                            throw new JSONException("create writeUsing Writer error", e);
                        }
                    }

                    if (writeUsingWriter == null && fieldInfo.fieldClassMixIn) {
                        writeUsingWriter = ObjectWriterProvider.VoidObjectWriter.INSTANCE;
                    }

                    FieldWriter fieldWriter = createFieldWriter(
                            provider,
                            objectClass,
                            fieldName,
                            fieldInfo.ordinal,
                            fieldInfo.features,
                            fieldInfo.format,
                            fieldInfo.label,
                            method,
                            writeUsingWriter
                    );

                    FieldWriter origin = fieldWriterMap.get(fieldWriter.fieldName);
                    if (origin == null) {
                        fieldWriterMap.put(fieldWriter.fieldName, fieldWriter);
                    }

                    if (origin != null && origin.compareTo(fieldWriter) > 0) {
                        fieldWriterMap.put(fieldName, fieldWriter);
                    }
                });

                fieldWriters = new ArrayList<>(fieldWriterMap.values());
            }
        }

        long writerFeatures = features | beanInfo.writerFeatures;
        if ((!fieldBased) && Throwable.class.isAssignableFrom(objectClass)) {
            return new ObjectWriterException(objectClass, writerFeatures, fieldWriters);
        }

        handleIgnores(beanInfo, fieldWriters);

        if (beanInfo.alphabetic) {
            Collections.sort(fieldWriters);
        }

        ObjectWriterAdapter writerAdapter = null;

        boolean googleCollection = false;
        String typeName = objectClass.getName();
        googleCollection =
                "com.google.common.collect.AbstractMapBasedMultimap$RandomAccessWrappedList".equals(typeName)
                        || "com.google.common.collect.AbstractMapBasedMultimap$WrappedSet".equals(typeName);
        if (!googleCollection && beanInfo.rootName == null) {
            switch (fieldWriters.size()) {
                case 1:
                    if ((fieldWriters.get(0).features & FieldInfo.VALUE_MASK) == 0) {
                        writerAdapter = new ObjectWriter1(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    }
                    break;
                case 2:
                    writerAdapter = new ObjectWriter2(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 3:
                    writerAdapter = new ObjectWriter3(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 4:
                    writerAdapter = new ObjectWriter4(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 5:
                    writerAdapter = new ObjectWriter5(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 6:
                    writerAdapter = new ObjectWriter6(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 7:
                    writerAdapter = new ObjectWriter7(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 8:
                    writerAdapter = new ObjectWriter8(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 9:
                    writerAdapter = new ObjectWriter9(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 10:
                    writerAdapter = new ObjectWriter10(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 11:
                    writerAdapter = new ObjectWriter11(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                case 12:
                    writerAdapter = new ObjectWriter12(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
                    break;
                default:
                    break;
            }
        }
        if (writerAdapter == null) {
            if (beanInfo.rootName != null) {
                writerAdapter = new ObjectWriterRootName(objectClass, beanInfo.typeKey, beanInfo.typeName, beanInfo.rootName, writerFeatures, fieldWriters);
            } else {
                writerAdapter = new ObjectWriterAdapter(objectClass, beanInfo.typeKey, beanInfo.typeName, writerFeatures, fieldWriters);
            }
        }

        if (beanInfo.serializeFilters != null) {
            configSerializeFilters(beanInfo, writerAdapter);
        }

        return writerAdapter;
    }

    protected static String getFieldName(
            Class objectClass,
            BeanInfo beanInfo,
            FieldInfo fieldInfo,
            Method method
    ) {
        String fieldName;
        if (fieldInfo.fieldName == null || fieldInfo.fieldName.isEmpty()) {
            fieldName = BeanUtils.getterName(method, beanInfo.kotlin, beanInfo.namingStrategy);

            char c0 = '\0', c1;
            int len = fieldName.length();
            if (len > 0) {
                c0 = fieldName.charAt(0);
            }

            if ((len == 1 && c0 >= 'a' && c0 <= 'z')
                    || (len > 2 && c0 >= 'A' && c0 <= 'Z' && (c1 = fieldName.charAt(1)) >= 'A' && c1 <= 'Z')
            ) {
                char[] chars = fieldName.toCharArray();
                if (c0 >= 'a' && c0 <= 'z') {
                    chars[0] = (char) (chars[0] - 32);
                } else {
                    chars[0] = (char) (chars[0] + 32);
                }
                String fieldName1 = new String(chars);
                Field field = BeanUtils.getDeclaredField(objectClass, fieldName1);

                if (field != null && (len == 1 || Modifier.isPublic(field.getModifiers()))) {
                    fieldName = field.getName();
                }
            }
        } else {
            fieldName = fieldInfo.fieldName;
        }
        return fieldName;
    }

    protected static void configSerializeFilters(BeanInfo beanInfo, ObjectWriterAdapter writerAdapter) {
        for (Class<? extends Filter> filterClass : beanInfo.serializeFilters) {
            if (!Filter.class.isAssignableFrom(filterClass)) {
                continue;
            }

            try {
                Filter filter = filterClass.newInstance();
                writerAdapter.setFilter(filter);
            } catch (InstantiationException | IllegalAccessException ignored) {
                //ignored
            }
        }
    }

    protected void handleIgnores(BeanInfo beanInfo, List<FieldWriter> fieldWriters) {
        if (beanInfo.ignores == null || beanInfo.ignores.length == 0) {
            return;
        }

        for (int i = fieldWriters.size() - 1; i >= 0; i--) {
            FieldWriter fieldWriter = fieldWriters.get(i);
            for (String ignore : beanInfo.ignores) {
                if (ignore.equals(fieldWriter.fieldName)) {
                    fieldWriters.remove(i);
                    break;
                }
            }
        }
    }

    public <T> FieldWriter<T> createFieldWriter(String fieldName, String format, Field field) {
        return createFieldWriter(JSONFactory.defaultObjectWriterProvider, fieldName, 0, 0L, format, null, field, null);
    }

    public <T> FieldWriter<T> createFieldWriter(
            String fieldName,
            int ordinal,
            long features,
            String format,
            Field field
    ) {
        return createFieldWriter(JSONFactory.defaultObjectWriterProvider, fieldName, ordinal, features, format, null, field, null);
    }

    public <T> FieldWriter<T> createFieldWriter(
            String fieldName,
            int ordinal,
            long features,
            String format,
            String label,
            Field field,
            ObjectWriter initObjectWriter
    ) {
        return createFieldWriter(JSONFactory.defaultObjectWriterProvider, fieldName, ordinal, features, format, label, field, initObjectWriter);
    }

    public <T> FieldWriter<T> createFieldWriter(
            ObjectWriterProvider provider,
            String fieldName,
            int ordinal,
            long features,
            String format,
            String label,
            Field field,
            ObjectWriter initObjectWriter
    ) {
        Class<?> declaringClass = field.getDeclaringClass();
        Method method = null;

        if (declaringClass == Throwable.class) {
            if (field.getName().equals("stackTrace")) {
                method = BeanUtils.getMethod(Throwable.class, "getStackTrace");
            }
        }

        if (method != null) {
            return createFieldWriter(provider, (Class<T>) Throwable.class, fieldName, ordinal, features, format, label, method, initObjectWriter);
        }

        Class<?> fieldClass = field.getType();
        Type fieldType = field.getGenericType();

        if (initObjectWriter != null) {
//            if (fieldClass == byte.class) {
//                fieldType = fieldClass = Byte.class;
//            } else if (fieldClass == short.class) {
//                fieldType = fieldClass = Short.class;
//            } else if (fieldClass == int.class) {
//                fieldType = fieldClass = Integer.class;
//            } else if (fieldClass == long.class) {
//                fieldType = fieldClass = Long.class;
//            } else if (fieldClass == float.class) {
//                fieldType = fieldClass = Float.class;
//            } else if (fieldClass == double.class) {
//                fieldType = fieldClass = Double.class;
//            } else if (fieldClass == boolean.class) {
//                fieldType = fieldClass = Boolean.class;
//            }

            FieldWriterObject objImp = new FieldWriterObject(fieldName, ordinal, features, format, label, fieldType, fieldClass, field, null);
            objImp.initValueClass = fieldClass;
            if (initObjectWriter != ObjectWriterProvider.VoidObjectWriter.INSTANCE) {
                objImp.initObjectWriter = initObjectWriter;
            }
            return objImp;
        }

        if (fieldClass == boolean.class) {
            return new FieldWriterBoolValField(fieldName, ordinal, features, format, label, field, fieldClass);
        }

        if (fieldClass == byte.class) {
            return new FieldWriterInt8ValField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == short.class) {
            return new FieldWriterInt16ValField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == int.class) {
            return new FieldWriterInt32Val(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == long.class) {
            if (format == null || format.isEmpty() || "string".equals(format)) {
                return new FieldWriterInt64ValField(fieldName, ordinal, features, format, label, field);
            }
            return new FieldWriterMillisField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == float.class) {
            return new FieldWriterFloatValField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == Float.class) {
            return new FieldWriterFloatField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == double.class) {
            return new FieldWriterDoubleValField(fieldName, ordinal, format, label, field);
        }

        if (fieldClass == Double.class) {
            return new FieldWriterDoubleField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == char.class) {
            return new FieldWriterCharValField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == BigInteger.class) {
            return new FieldWriterBigIntField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == BigDecimal.class) {
            return new FieldWriterBigDecimalField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == java.util.Date.class) {
            return new FieldWriterDateField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass == String.class) {
            return new FieldWriterStringField(fieldName, ordinal, features, format, label, field);
        }

        if (fieldClass.isEnum()) {
            BeanInfo beanInfo = new BeanInfo();
            provider.getBeanInfo(beanInfo, fieldClass);

            boolean writeEnumAsJavaBean = beanInfo.writeEnumAsJavaBean;
            if (!writeEnumAsJavaBean) {
                ObjectWriter objectWriter = provider.cache.get(fieldClass);
                if (objectWriter != null && !(objectWriter instanceof ObjectWriterImplEnum)) {
                    writeEnumAsJavaBean = true;
                }
            }

            Member enumValueField = BeanUtils.getEnumValueField(fieldClass, provider);
            if (enumValueField == null && !writeEnumAsJavaBean) {
                String[] enumAnnotationNames = BeanUtils.getEnumAnnotationNames(fieldClass);
                if (enumAnnotationNames == null) {
                    return new FieldWriterEnum(fieldName, ordinal, features, format, label, fieldType, (Class<? extends Enum>) fieldClass, field, null);
                }
            }
        }

        if (fieldClass == List.class || fieldClass == ArrayList.class) {
            Type itemType = null;
            if (fieldType instanceof ParameterizedType) {
                itemType = ((ParameterizedType) fieldType).getActualTypeArguments()[0];
            }
            return new FieldWriterListField(fieldName, itemType, ordinal, features, format, label, fieldType, fieldClass, field);
        }

        if (fieldClass.isArray() && !fieldClass.getComponentType().isPrimitive()) {
            Class<?> itemClass = fieldClass.getComponentType();
            return new FieldWriterObjectArrayField(fieldName, itemClass, ordinal, features, format, label, itemClass, fieldClass, field);
        }

        return new FieldWriterObject(fieldName, ordinal, features, format, label, field.getGenericType(), fieldClass, field, null);
    }

    public <T> FieldWriter<T> createFieldWriter(Class<T> objectType,
                                                String fieldName,
                                                String dateFormat,
                                                Method method) {
        return createFieldWriter(objectType, fieldName, 0, 0, dateFormat, method);
    }

    public <T> FieldWriter<T> createFieldWriter(
            Class<T> objectType,
            String fieldName,
            int ordinal,
            long features,
            String format,
            Method method) {
        return createFieldWriter(null, objectType, fieldName, ordinal, features, format, null, method, null);
    }

    public <T> FieldWriter<T> createFieldWriter(
            ObjectWriterProvider provider,
            Class<T> objectType,
            String fieldName,
            int ordinal,
            long features,
            String format,
            String label,
            Method method,
            ObjectWriter initObjectWriter
    ) {
        Class<?> fieldClass = method.getReturnType();
        Type fieldType = method.getGenericReturnType();

        try {
            if (fieldClass == Object.class
                    && method.getDeclaringClass().getName().equals("java.util.HashMap$Node")) {
                String methodName = method.getName();
                if (methodName.equals("getValue") || methodName.equals("getKey")) {
                    method = Map.Entry.class.getMethod(methodName);
                }
            }
            method.setAccessible(true);
        } catch (Exception ignored) {
            // ignored
        }

        if (initObjectWriter == null && provider != null) {
            initObjectWriter = getInitWriter(provider, fieldClass);
        }

        if (initObjectWriter != null) {
            FieldWriterObjectMethod objMethod = new FieldWriterObjectMethod(fieldName, ordinal, features, format, label, fieldType, fieldClass, method);
            objMethod.initValueClass = fieldClass;
            if (initObjectWriter != ObjectWriterProvider.VoidObjectWriter.INSTANCE) {
                objMethod.initObjectWriter = initObjectWriter;
            }
            return objMethod;
        }

        if (fieldName == null) {
            fieldName = BeanUtils.getterName(method, false, null);
        }

        if (fieldClass == boolean.class || fieldClass == Boolean.class) {
            return new FieldWriterBoolMethod(fieldName, ordinal, features, format, label, method, fieldClass);
        }

        if (fieldClass == int.class || fieldClass == Integer.class) {
            return new FieldWriterInt32Method(fieldName, ordinal, features, format, label, method, fieldClass);
        }

        if (fieldClass == float.class || fieldClass == Float.class) {
            return new FieldWriterFloatMethod<>(fieldName, ordinal, features, format, label, fieldClass, fieldClass, method);
        }

        if (fieldClass == double.class || fieldClass == Double.class) {
            return new FieldWriterDoubleMethod<>(fieldName, ordinal, features, format, label, fieldClass, fieldClass, method);
        }

        if (fieldClass == long.class || fieldClass == Long.class) {
            if (format == null || format.isEmpty() || "string".equals(format)) {
                return new FieldWriterInt64Method(fieldName, ordinal, features, format, label, method, fieldClass);
            }

            return new FieldWriterMillisMethod(fieldName, ordinal, features, format, label, fieldClass, method);
        }

        if (fieldClass == short.class || fieldClass == Short.class) {
            return new FieldWriterInt16Method(fieldName, ordinal, features, format, label, method, fieldClass);
        }

        if (fieldClass == byte.class || fieldClass == Byte.class) {
            return new FieldWriterInt8Method(fieldName, ordinal, features, format, label, method, fieldClass);
        }

        if (fieldClass == char.class || fieldClass == Character.class) {
            return new FieldWriterCharMethod(fieldName, ordinal, features, format, label, method, fieldClass);
        }

        if (fieldClass == BigDecimal.class) {
            return new FieldWriterBigDecimalMethod<>(fieldName, ordinal, features, format, label, method);
        }

        if (fieldClass.isEnum() && BeanUtils.getEnumValueField(fieldClass, provider) == null && !BeanUtils.isWriteEnumAsJavaBean(fieldClass)
        ) {
            String[] enumAnnotationNames = BeanUtils.getEnumAnnotationNames(fieldClass);
            if (enumAnnotationNames == null) {
                return new FieldWriterEnumMethod(fieldName, ordinal, features, format, label, fieldClass, method);
            }
        }

        if (fieldClass == Date.class) {
            if (format != null) {
                format = format.trim();

                if (format.isEmpty()) {
                    format = null;
                }
            }

            return new FieldWriterDateMethod(fieldName, ordinal, features, format, label, fieldClass, method);
        }

        if (fieldClass == String.class) {
            return new FieldWriterStringMethod(fieldName, ordinal, format, label, features, method);
        }

        if (fieldClass == List.class) {
            Type itemType;
            if (fieldType instanceof ParameterizedType) {
                itemType = ((ParameterizedType) fieldType).getActualTypeArguments()[0];
            } else {
                itemType = Object.class;
            }
            return new FieldWriterListMethod(fieldName, itemType, ordinal, features, format, label, method, fieldType, fieldClass);
        }

        if (fieldClass == Float[].class) {
            return new FieldWriterObjectArrayMethod(fieldName, Float.class, ordinal, features, format, label, fieldType, fieldClass, method);
        }

        if (fieldClass == Double[].class) {
            return new FieldWriterObjectArrayMethod(fieldName, Double.class, ordinal, features, format, label, fieldType, fieldClass, method);
        }

        if (fieldClass == BigDecimal[].class) {
            return new FieldWriterObjectArrayMethod(fieldName, BigDecimal.class, ordinal, features, format, label, fieldType, fieldClass, method);
        }

        return new FieldWriterObjectMethod(fieldName, ordinal, features, format, label, fieldType, fieldClass, method);
    }

    public <T> FieldWriter createFieldWriter(String fieldName, ToLongFunction<T> function) {
        return new FieldWriterInt64ValFunc(fieldName, 0, 0, null, null, null, function);
    }

    public <T> FieldWriter createFieldWriter(String fieldName, ToIntFunction<T> function) {
        return new FieldWriterInt32ValFunc(fieldName, 0, 0, null, null, null, function);
    }

    public <T> FieldWriter createFieldWriter(String fieldName, ToShortFunction<T> function) {
        return new FieldWriterInt16ValFunc(fieldName, 0, 0, null, null, null, function);
    }

    public <T> FieldWriter createFieldWriter(String fieldName, ToByteFunction<T> function) {
        return new FieldWriterInt8ValFunc(fieldName, 0, 0, null, null, null, function);
    }

    public <T> FieldWriter createFieldWriter(String fieldName, ToFloatFunction<T> function) {
        return new FieldWriterFloatValueFunc(fieldName, 0, 0L, null, null, null, function);
    }

    public <T> FieldWriter createFieldWriter(String fieldName, ToDoubleFunction<T> function) {
        return new FieldWriterDoubleValueFunc(fieldName, 0, 0, null, null, null, function);
    }

    public <T> FieldWriter createFieldWriter(String fieldName, Predicate<T> function) {
        return new FieldWriterBoolValFunc(fieldName, 0, 0, null, null, null, function);
    }

    public <T, V> FieldWriter createFieldWriter(
            String fieldName,
            Class fieldClass,
            Function<T, V> function
    ) {
        return createFieldWriter(null, null, fieldName, 0, 0, null, null, fieldClass, fieldClass, null, function);
    }

    public <T, V> FieldWriter createFieldWriter(
            String fieldName,
            Type fieldType,
            Class fieldClass,
            Function<T, V> function
    ) {
        return createFieldWriter(null, null, fieldName, 0, 0, null, null, fieldType, fieldClass, null, function);
    }

    public <T, V> FieldWriter createFieldWriter(
            String fieldName,
            long features,
            String format,
            Class fieldClass,
            Function<T, V> function
    ) {
        return createFieldWriter(null, null, fieldName, 0, features, format, null, fieldClass, fieldClass, null, function);
    }

    public <T, V> FieldWriter<T> createFieldWriter(
            ObjectWriterProvider provider,
            Class<T> objectClass,
            String fieldName,
            int ordinal,
            long features,
            String format,
            String label,
            Type fieldType,
            Class<V> fieldClass,
            Method method,
            Function<T, V> function
    ) {
        if (fieldClass == Byte.class) {
            return new FieldWriterInt8Func(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == Short.class) {
            return new FieldWriterInt16Func(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == Integer.class) {
            return new FieldWriterInt32Func(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == Long.class) {
            return new FieldWriterInt64Func(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == BigInteger.class) {
            return new FieldWriterBigIntFunc(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == BigDecimal.class) {
            return new FieldWriterBigDecimalFunc(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == String.class) {
            return new FieldWriterStringFunc(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == Date.class) {
            return new FieldWriterDateFunc(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass == UUID.class) {
            return new FieldWriterUUIDFunc(fieldName, ordinal, features, format, label, fieldType, fieldClass, method, function);
        }

        if (Calendar.class.isAssignableFrom(fieldClass)) {
            return new FieldWriterCalendarFunc(fieldName, ordinal, features, format, label, method, function);
        }

        if (fieldClass.isEnum()) {
            BeanInfo beanInfo = new BeanInfo();
            if (provider == null) {
                provider = JSONFactory.defaultObjectWriterProvider;
            }
            provider.getBeanInfo(beanInfo, fieldClass);

            boolean writeEnumAsJavaBean = beanInfo.writeEnumAsJavaBean;
            if (!writeEnumAsJavaBean) {
                ObjectWriter objectWriter = provider.cache.get(fieldClass);
                if (objectWriter != null && !(objectWriter instanceof ObjectWriterImplEnum)) {
                    writeEnumAsJavaBean = true;
                }
            }

            if (!writeEnumAsJavaBean && BeanUtils.getEnumValueField(fieldClass, provider) == null) {
                String[] enumAnnotationNames = BeanUtils.getEnumAnnotationNames(fieldClass);
                if (enumAnnotationNames == null) {
                    return new FieldWriterEnumFunc(fieldName, ordinal, features, format, label, fieldType, fieldClass, method, function);
                }
            }
        }

        if (fieldType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) fieldType;
            Type rawType = parameterizedType.getRawType();
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();

            if (rawType == List.class || rawType == ArrayList.class) {
                if (actualTypeArguments.length == 1) {
                    Type itemType = actualTypeArguments[0];
                    if (itemType == String.class) {
                        return new FieldWriterListStrFunc(fieldName, ordinal, features, format, label, method, function, fieldType, fieldClass);
                    }
                    return new FieldWriterListFunc(fieldName, ordinal, features, format, label, itemType, method, function, fieldType, fieldClass);
                }
            }
        }

        if (Modifier.isFinal(fieldClass.getModifiers())) {
            return new FieldWriterObjectFuncFinal(fieldName, ordinal, features, format, label, fieldType, fieldClass, method, function);
        }

        return new FieldWriterObjectFunc(fieldName, ordinal, features, format, label, fieldType, fieldClass, method, function);
    }

    protected ObjectWriter getInitWriter(ObjectWriterProvider provider, Class fieldClass) {
        if (fieldClass == Date.class) {
            if ((provider.userDefineMask & ObjectWriterProvider.TYPE_DATE_MASK) != 0) {
                ObjectWriter objectWriter = provider.cache.get(fieldClass);
                if (objectWriter != ObjectWriterImplDate.INSTANCE) {
                    return objectWriter;
                }
            }
        } else if (fieldClass == long.class || fieldClass == Long.class) {
            if ((provider.userDefineMask & ObjectWriterProvider.TYPE_INT64_MASK) != 0) {
                ObjectWriter objectWriter = provider.cache.get(Long.class);
                if (objectWriter != ObjectWriterImplInt64.INSTANCE) {
                    return objectWriter;
                }
            }
        } else if (fieldClass == BigDecimal.class) {
            if ((provider.userDefineMask & ObjectWriterProvider.TYPE_DECIMAL_MASK) != 0) {
                ObjectWriter objectWriter = provider.cache.get(fieldClass);
                if (objectWriter != ObjectWriterImplBigDecimal.INSTANCE) {
                    return objectWriter;
                }
            }
        } else if (Enum.class.isAssignableFrom(fieldClass)) {
            ObjectWriter objectWriter = provider.cache.get(fieldClass);
            if (!(objectWriter instanceof ObjectWriterImplEnum)) {
                return objectWriter;
            }
        }
        return null;
    }
}
