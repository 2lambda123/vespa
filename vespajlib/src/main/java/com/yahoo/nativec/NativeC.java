// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.nativec;

import com.sun.jna.Native;
import com.sun.jna.Platform;

class NativeC {
    static Throwable loadLibrary(Class<?> cls) {
        if (Platform.isLinux()) {
            try {
                Native.register(cls, Platform.C_LIBRARY_NAME);
            } catch (Throwable e) {
                return e;
            }
        } else {
            return new RuntimeException("Platform is unsupported. Only supported on Linux.");
        }
        return null;
    }

}
