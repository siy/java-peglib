package com.example;

import java.util.Map;
import java.util.List;
import java.util.function.Function;

class DeepGenerics<T extends Comparable<? super T> & Cloneable> {
    Map<String, List<? extends Comparable<? super Integer>>> deepMap;
    <U extends Number & Comparable<? super U>> Function<? super U, ? extends List<? extends U>> transformer() {
        return null;
    }
    List<? extends Map<? super String, ? extends List<? super Integer>>> nested;
}
