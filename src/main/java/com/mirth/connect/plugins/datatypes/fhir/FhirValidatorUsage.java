/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lets the engine drop the validators, and the FHIR specifications (R5 alone is about 300 MB), that
 * no deployed channel uses any more.
 *
 * The options in use come from the deployed channels (server.FhirChannelPlugin, which also calls
 * {@link #releaseNow} after every deploy and undeploy). Validators with other options, such as
 * those of FhirTools in a script, are kept for {@link #IDLE_MILLIS} after their last use; a check
 * once a minute releases them also when nothing else happens.
 */
public final class FhirValidatorUsage {

    public static final long IDLE_MILLIS = 15 * 60 * 1000L;

    private static volatile Supplier<List<Map<String, String>>> inUse = Collections::emptyList;

    private static ScheduledExecutorService timer;

    private FhirValidatorUsage() {}

    /** Set by the server plugin: the validator options of the deployed channels. */
    public static void setInUse(Supplier<List<Map<String, String>>> supplier) {
        inUse = supplier;
    }

    static List<Map<String, String>> inUse() {
        return inUse.get();
    }

    /** Starts the periodic check, once the engine validates. */
    public static synchronized void startPeriodicRelease(FhirEngine engine) {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "FHIR validator release");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                releaseNow(engine, IDLE_MILLIS);
            } catch (RuntimeException e) {
                // Next minute again; the deployed channels could not be read, for example while the server stops.
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /** Drops what the deployed channels do not use and was not used for {@code idleMillis}. */
    public static void releaseNow(FhirEngine engine, long idleMillis) {
        engine.retainValidators(inUse(), idleMillis);
    }
}
