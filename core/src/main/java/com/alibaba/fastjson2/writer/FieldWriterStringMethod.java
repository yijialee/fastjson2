package com.alibaba.fastjson2.writer;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONWriter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class FieldWriterStringMethod<T>
        extends FieldWriter<T> {
    FieldWriterStringMethod(
            String fieldName,
            int ordinal,
            String format,
            String label,
            long features,
            Method method
    ) {
        super(fieldName, ordinal, features, format, label, String.class, String.class, null, method);
    }

    @Override
    public Object getFieldValue(Object object) {
        try {
            return method.invoke(object);
        } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
            throw new JSONException("invoke getter method error, " + fieldName, e);
        }
    }

    @Override
    public void writeValue(JSONWriter jsonWriter, T object) {
        Object fieldValue;
        fieldValue = getFieldValue(object);
        String value = (String) fieldValue;

        if (trim && value != null) {
            value = value.trim();
        }

        if (symbol && jsonWriter.jsonb) {
            jsonWriter.writeSymbol(value);
        } else {
            if (raw) {
                jsonWriter.writeRaw(value);
            } else {
                jsonWriter.writeString(value);
            }
        }
    }

    @Override
    public boolean write(JSONWriter jsonWriter, T object) {
        String value;
        try {
            value = (String) getFieldValue(object);
        } catch (JSONException error) {
            if ((jsonWriter.getFeatures(features) | JSONWriter.Feature.IgnoreNonFieldGetter.mask) != 0) {
                return false;
            }
            throw error;
        }

        long features = this.features | jsonWriter.getFeatures();
        if (value == null) {
            if ((features & (JSONWriter.Feature.WriteNulls.mask | JSONWriter.Feature.NullAsDefaultValue.mask | JSONWriter.Feature.WriteNullStringAsEmpty.mask)) == 0) {
                return false;
            }
        } else if (trim) {
            value = value.trim();
        }

        if (value != null
                && value.isEmpty()
                && (features & JSONWriter.Feature.IgnoreEmpty.mask) != 0
        ) {
            return false;
        }

        writeString(jsonWriter, value);
        return true;
    }
}
