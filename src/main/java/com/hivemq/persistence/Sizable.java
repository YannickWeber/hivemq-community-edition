package com.hivemq.persistence;

import com.hivemq.extension.sdk.api.annotations.ThreadSafe;

/**
 * Allows the estimation of the in memory size of an object.
 *
 * @author Georg Held
 */
public interface Sizable {

    int SIZE_NOT_CALCULATED = -1;

    @ThreadSafe
    int getEstimatedSize();
}
