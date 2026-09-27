package io.github.mojolowjo.crossblades.platform;

import java.util.ServiceLoader;

/** Finds the loader-specific {@link IPlatformHelper} (listed in each loader's META-INF/services). */
public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    private static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No implementation of " + type.getName() + " found"));
    }
}
