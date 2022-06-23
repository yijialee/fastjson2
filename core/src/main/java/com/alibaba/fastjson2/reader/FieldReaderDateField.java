package com.alibaba.fastjson2.reader;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.util.IOUtils;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

final class FieldReaderDateField<T>
        extends FieldReaderObjectField<T> {
    private ObjectReaderImplDate dateReader;
    DateTimeFormatter formatter;
    final Locale locale;
    final boolean useSimpleFormatter;

    final boolean formatISO8601;
    final boolean formatUnixTime;
    final boolean formatMillis;
    final boolean formatHasDay;
    final boolean formatHasHour;

    FieldReaderDateField(String fieldName, Class fieldType, int ordinal, long features, String format, Locale locale, Date defaultValue, JSONSchema schema, Field field) {
        super(fieldName, fieldType, fieldType, ordinal, features, format, defaultValue, schema, field);
        this.locale = locale;
        this.useSimpleFormatter = "yyyyMMddHHmmssSSSZ".equals(format);
        boolean formatUnixTime = false, formatISO8601 = false, formatMillis = false, hasDay = false, hasHour = false;
        if (format != null) {
            switch (format) {
                case "unixtime":
                    formatUnixTime = true;
                    break;
                case "iso8601":
                    formatISO8601 = true;
                    break;
                case "millis":
                    formatMillis = true;
                    break;
                default:
                    hasDay = format.indexOf('d') != -1;
                    hasHour = format.indexOf('H') != -1
                            || format.indexOf('h') != -1
                            || format.indexOf('K') != -1
                            || format.indexOf('k') != -1;
                    break;
            }
        }
        this.formatUnixTime = formatUnixTime;
        this.formatMillis = formatMillis;
        this.formatISO8601 = formatISO8601;

        this.formatHasDay = hasDay;
        this.formatHasHour = hasHour;
    }

    @Override
    public ObjectReader getObjectReader(JSONReader jsonReader) {
        if (dateReader == null) {
            dateReader = format == null
                    ? ObjectReaderImplDate.INSTANCE
                    : new ObjectReaderImplDate(format, locale);
        }
        return dateReader;
    }

    @Override
    public void readFieldValue(JSONReader jsonReader, T object) {
        Date fieldValue;
        if (jsonReader.isInt()) {
            long millis = jsonReader.readInt64Value();

            getObjectReader(jsonReader);
            if (dateReader.formatUnixTime) {
                millis *= 1000;
            }

            fieldValue = new Date(millis);
        } else if (jsonReader.isNull()) {
            jsonReader.readNull();
            fieldValue = null;
        } else if (useSimpleFormatter) {
            String str = jsonReader.readString();
            try {
                fieldValue = new SimpleDateFormat(format).parse(str);
            } catch (ParseException e) {
                throw new JSONException(jsonReader.info("parse error : " + str), e);
            }
        } else {
            long millis;
            if (format != null) {
                String str = jsonReader.readString();
                if ((formatUnixTime || formatMillis) && IOUtils.isNumber(str)) {
                    millis = Long.parseLong(str);
                    if (formatUnixTime) {
                        millis *= 1000L;
                    }
                } else {
                    DateTimeFormatter formatter = getFormatter(jsonReader.getLocale());
                    LocalDateTime ldt;
                    if (!formatHasHour) {
                        ldt = LocalDateTime.of(LocalDate.parse(str, formatter), LocalTime.MIN);
                    } else {
                        ldt = LocalDateTime.parse(str, formatter);
                    }

                    ZonedDateTime zdt = ldt.atZone(jsonReader.getContext().getZoneId());
                    millis = zdt.toInstant().toEpochMilli();
                }
            } else {
                millis = jsonReader.readMillisFromString();
            }
            fieldValue = new Date(millis);
        }

        try {
            field.set(object, fieldValue);
        } catch (Exception e) {
            throw new JSONException(jsonReader.info("set " + fieldName + " error"), e);
        }
    }

    @Override
    public void accept(T object, Object value) {
        if (value instanceof String) {
            String str = (String) value;

            long millis;
            if ((format == null || formatUnixTime || formatMillis) && IOUtils.isNumber(str)) {
                millis = Long.parseLong(str);
                if (formatUnixTime) {
                    millis *= 1000L;
                }
            } else {
                DateTimeFormatter formatter = getFormatter(null);
                LocalDateTime ldt;
                if (!formatHasHour) {
                    ldt = LocalDateTime.of(LocalDate.parse(str, formatter), LocalTime.MIN);
                } else {
                    ldt = LocalDateTime.parse(str, formatter);
                }

                ZonedDateTime zdt = ldt.atZone(ZoneId.systemDefault());
                millis = zdt.toInstant().toEpochMilli();
            }
            value = new java.util.Date(millis);
        } else if (value instanceof Integer) {
            long millis = ((Integer) value).intValue();
            if (dateReader.formatUnixTime) {
                millis *= 1000;
            }
            value = new Date(millis);
        }

        try {
            field.set(object, value);
        } catch (Exception e) {
            throw new JSONException("set " + fieldName + " error", e);
        }
    }

    private DateTimeFormatter getFormatter(Locale locale) {
        if (formatter != null && locale == null) {
            return formatter;
        }

        String format = this.format.replaceAll("aa", "a");

        if (locale != null && locale != Locale.getDefault()) {
            return DateTimeFormatter.ofPattern(format, locale);
        }

        if (this.locale != null) {
            return formatter = DateTimeFormatter.ofPattern(format, this.locale);
        }

        return formatter = DateTimeFormatter.ofPattern(format);
    }
}