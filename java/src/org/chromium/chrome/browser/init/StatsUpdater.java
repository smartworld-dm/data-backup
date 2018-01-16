/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.init;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;

import java.util.Calendar;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;
import java.util.concurrent.Semaphore;

import org.chromium.base.Log;
import org.chromium.chrome.browser.ChromeVersionInfo;
import org.chromium.chrome.browser.util.DateUtils;
import org.chromium.chrome.browser.util.PackageUtils;

public class StatsUpdater {
    public static final long MILLISECONDS_IN_A_DAY = 86400 * 1000;
    public static final long MILLISECONDS_IN_A_WEEK = 7 * 86400 * 1000;

    private static final String PREF_NAME = "StatsPreferences";
    private static final String MILLISECONDS_NAME = "Milliseconds";
    private static final String MILLISECONDS_FOR_WEEKLY_STATS_NAME = "MillisecondsForWeeklyStats";
    private static final String MONTH_NAME = "Month";
    private static final String YEAR_NAME = "Year";
    private static final String WEEK_OF_INSTALLATION_NAME = "WeekOfInstallation";
    private static final String PROMO_NAME = "Promo";

    private static final String SERVER_REQUEST = "https://laptop-updates.brave.com/1/usage/android?daily=%1$s&weekly=%2$s&monthly=%3$s&platform=android&version=%4$s&first=%5$s&channel=stable&woi=%6$s&ref=%7$s";

    private static Semaphore mAvailable = new Semaphore(1);

    public static void UpdateStats(Context context) {
        try {
            mAvailable.acquire();
            try {
                Calendar currentTime = Calendar.getInstance();
                long milliSeconds = currentTime.getTimeInMillis();

                StatsObject previousObject = StatsUpdater.GetPreferences(context);
                boolean firstRun = (0 == previousObject.mMilliSeconds);
                boolean daily = false;
                boolean weekly = false;
                boolean monthly = false;

                long milliSecondsOfTheCurrentDay = currentTime.get(currentTime.HOUR_OF_DAY) * 60 * 60 * 1000
                      + currentTime.get(currentTime.MINUTE) * 60 * 1000 + currentTime.get(currentTime.SECOND) * 1000
                      + currentTime.get(currentTime.MILLISECOND);
                if (milliSeconds - previousObject.mMilliSeconds >= MILLISECONDS_IN_A_DAY
                      || milliSecondsOfTheCurrentDay < milliSeconds - previousObject.mMilliSeconds) {
                    daily = true;
                }
                if (milliSeconds - previousObject.mMilliSecondsForWeeklyStat >= MILLISECONDS_IN_A_WEEK) {
                    weekly = true;
                }
                if (currentTime.get(currentTime.MONTH) != previousObject.mMonth || currentTime.get(currentTime.YEAR) != previousObject.mYear) {
                    monthly = true;
                }

                if (!firstRun && !daily && !weekly && !monthly) {
                    // We have nothing to update
                    return;
                }

                boolean updated = StatsUpdater.UpdateServer(context, firstRun, daily, weekly, monthly);
                if (updated) {
                    StatsObject currentObject = new StatsObject();
                    if (daily) {
                        currentObject.mMilliSeconds = milliSeconds;
                    } else {
                        currentObject.mMilliSeconds = previousObject.mMilliSeconds;
                    }
                    if (weekly) {
                        currentObject.mMilliSecondsForWeeklyStat = milliSeconds;
                    } else {
                        currentObject.mMilliSecondsForWeeklyStat = previousObject.mMilliSecondsForWeeklyStat;
                    }
                    if (monthly) {
                        currentObject.mMonth = currentTime.get(currentTime.MONTH);
                        currentObject.mYear = currentTime.get(currentTime.YEAR);
                    } else {
                        currentObject.mMonth = previousObject.mMonth;
                        currentObject.mYear = previousObject.mYear;
                    }
                    StatsUpdater.SetPreferences(context, currentObject);
                }
            } finally {
                mAvailable.release();
            }
        } catch (InterruptedException exc) {
        }
    }

    public static boolean UpdateServer(Context context, boolean firstRun, boolean daily, boolean weekly, boolean monthly) {
        String versionNumber = "0";
        PackageInfo info = null;
        try {
            info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
        } catch (NameNotFoundException e) {
        }

        if (null != info) {
            versionNumber = info.versionName;
        }
        if (versionNumber.equals("Developer Build")) {
            return false;
        }
        versionNumber = versionNumber.replace(" ", "%20");

        String woi = GetWeekOfInstallation(context);
        String ref = GetRef(context);

        String strQuery = String.format(SERVER_REQUEST, daily, weekly, monthly,
            versionNumber, firstRun, woi, ref);

        try {
            URL url = new URL(strQuery);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.connect();
                if (HttpURLConnection.HTTP_OK != connection.getResponseCode()) {
                    Log.e("STAT", "stat update error == " + connection.getResponseCode());

                    return false;
                }

                return true;
            } finally {
                connection.disconnect();
            }
        }
        catch (MalformedURLException e) {
        }
        catch (IOException e) {
        }
        catch (Exception e) {
        }

        return false;
    }

    public static StatsObject GetPreferences(Context context) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREF_NAME, 0);

        StatsObject statsObject = new StatsObject();

        statsObject.mMilliSeconds = sharedPref.getLong(MILLISECONDS_NAME, 0);
        statsObject.mMilliSecondsForWeeklyStat = sharedPref.getLong(MILLISECONDS_FOR_WEEKLY_STATS_NAME, 0);
        statsObject.mMonth = sharedPref.getInt(MONTH_NAME, 0);
        statsObject.mYear = sharedPref.getInt(YEAR_NAME, 0);

        return statsObject;
    }

    public static void SetPreferences(Context context, StatsObject statsObject) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREF_NAME, 0);

        SharedPreferences.Editor editor = sharedPref.edit();

        editor.putLong(MILLISECONDS_NAME, statsObject.mMilliSeconds);
        editor.putLong(MILLISECONDS_FOR_WEEKLY_STATS_NAME, statsObject.mMilliSecondsForWeeklyStat);
        editor.putInt(MONTH_NAME, statsObject.mMonth);
        editor.putInt(YEAR_NAME, statsObject.mYear);

        editor.apply();
    }

    private static String GetWeekOfInstallation(Context context) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREF_NAME, 0);

        String weekOfInstallation = sharedPref.getString(WEEK_OF_INSTALLATION_NAME, null);
        if (weekOfInstallation == null || weekOfInstallation.isEmpty()) {
            SharedPreferences.Editor editor = sharedPref.edit();
            // If this is an update installation, consider week of installation
            // is the first Monday of 2016
            //DateUtils.testGetPreviousMondayDate();
            weekOfInstallation = PackageUtils.isFirstInstall(context) ?
              DateUtils.getPreviousMondayDate(Calendar.getInstance()) : "2016-01-04";
            editor.putString(WEEK_OF_INSTALLATION_NAME, weekOfInstallation);
            editor.apply();
        }

        return weekOfInstallation;
    }

    private static String GetRef(Context context) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREF_NAME, 0);
        String ref = sharedPref.getString(PROMO_NAME, null);
        if (ref == null || ref.isEmpty()) {
            ref = "others";
        }
        return ref;
    }
}
