package com.alibaba.fastjson2.reader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.function.Function;

import java.lang.reflect.Type;
import java.util.*;

import static com.alibaba.fastjson2.reader.ObjectReaderImplList.*;

public final class ObjectReaderImplListStr
        implements ObjectReader {
    final Class listType;
    final Class instanceType;

    public ObjectReaderImplListStr(Class listType, Class instanceType) {
        this.listType = listType;
        this.instanceType = instanceType;
    }

    @Override
    public Object createInstance(long features) {
        if (instanceType == ArrayList.class) {
            return new ArrayList();
        }

        if (instanceType == LinkedList.class) {
            return new LinkedList();
        }

        try {
            return instanceType.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new JSONException("create list error, type " + instanceType);
        }
    }

    @Override
    public Object createInstance(Collection collection, long features) {
        if (listType.isInstance(collection)) {
            boolean typeMatch = true;
            for (Object item : collection) {
                if (!(item instanceof String)) {
                    typeMatch = false;
                    break;
                }
            }
            if (typeMatch) {
                return collection;
            }
        }

        Collection typedList = (Collection) createInstance(features);
        for (Object item : collection) {
            if (item == null || item instanceof String) {
                typedList.add(item);
                continue;
            }
            typedList.add(
                    JSON.toJSONString(item)
            );
        }
        return typedList;
    }

    @Override
    public Class getObjectClass() {
        return listType;
    }

    @Override
    public Object readJSONBObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
        Class instanceType = this.instanceType;

        if (jsonReader.nextIfNull()) {
            return null;
        }

        ObjectReader objectReader = jsonReader.checkAutoType(this.listType, 0, features);
        if (objectReader != null) {
            instanceType = objectReader.getObjectClass();
        }

        if (instanceType == CLASS_ARRAYS_LIST) {
            int entryCnt = jsonReader.startArray();
            String[] array = new String[entryCnt];
            for (int i = 0; i < entryCnt; ++i) {
                array[i] = jsonReader.readString();
            }
            return Arrays.asList(array);
        }

        int entryCnt = jsonReader.startArray();

        Function builder = null;
        Collection list;
        if (instanceType == ArrayList.class) {
            list = entryCnt > 0 ? new ArrayList(entryCnt) : new ArrayList();
        } else if (instanceType == JSONArray.class) {
            list = entryCnt > 0 ? new JSONArray(entryCnt) : new JSONArray();
        } else if (instanceType == CLASS_UNMODIFIABLE_COLLECTION) {
            list = new ArrayList();
            builder = (Function<Collection, Collection>) Collections::unmodifiableCollection;
        } else if (instanceType == CLASS_UNMODIFIABLE_LIST) {
            list = new ArrayList();
            builder = (Function<List, List>) Collections::unmodifiableList;
        } else if (instanceType == CLASS_UNMODIFIABLE_SET) {
            list = new LinkedHashSet();
            builder = (Function<Set, Set>) Collections::unmodifiableSet;
        } else if (instanceType == CLASS_SINGLETON) {
            list = new ArrayList();
            builder = (Function<Collection, Collection>) ((Collection collection) -> Collections.singleton(collection.iterator().next()));
        } else if (instanceType == CLASS_SINGLETON_LIST) {
            list = new ArrayList();
            builder = (Function<Collection, Collection>) ((Collection collection) -> Collections.singletonList(collection.iterator().next()));
        } else if (instanceType != null && instanceType != this.listType) {
            try {
                list = (Collection) instanceType.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                throw new JSONException(jsonReader.info("create instance error " + instanceType), e);
            }
        } else {
            list = (Collection) createInstance(jsonReader.context.features | features);
        }

        for (int i = 0; i < entryCnt; ++i) {
            list.add(jsonReader.readString());
        }

        if (builder != null) {
            list = (Collection) builder.apply(list);
        }

        return list;
    }

    @Override
    public Object readObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
        if (jsonReader.jsonb) {
            return readJSONBObject(jsonReader, fieldType, fieldName, 0);
        }

        if (jsonReader.readIfNull()) {
            return null;
        }

        boolean set = jsonReader.nextIfSet();
        Collection list = set
                ? new HashSet()
                : (Collection) createInstance(jsonReader.context.features | features);

        char ch = jsonReader.current();
        if (ch == '[') {
            jsonReader.next();
            for (; ; ) {
                if (jsonReader.nextIfArrayEnd()) {
                    break;
                }

                String item = jsonReader.readString();
                if (item == null && list instanceof SortedSet) {
                    continue;
                }
                list.add(item);
            }
        } else if (ch == '"' || ch == '\'' || ch == '{') {
            String str = jsonReader.readString();
            if (!str.isEmpty()) {
                list.add(str);
            }
        } else {
            throw new JSONException(jsonReader.info());
        }

        jsonReader.nextIfComma();

        return list;
    }
}
