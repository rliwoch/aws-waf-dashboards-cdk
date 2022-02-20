package com.myorg;

import java.util.UUID;

public class Helper {
    public static String RANDOM = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    public static String buildName(String prefix, String resourceName, String stackId) {
        return prefix + resourceName + RANDOM;
    }
}
