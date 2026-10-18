package com.ajctrl.sumiresync;

import android.app.job.JobParameters;
import android.content.ClipData;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PersistableBundle;

/** Real API 30 parcels, including the distinct objects delivered by Binder callbacks. */
public final class TestJobParameters {
    private TestJobParameters() {}

    public static JobParameters create(int jobId) {
        try {
            var constructor = JobParameters.class.getDeclaredConstructor(IBinder.class, int.class,
                    PersistableBundle.class, Bundle.class, ClipData.class, int.class, boolean.class,
                    Uri[].class, String[].class, Network.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, jobId, PersistableBundle.EMPTY, Bundle.EMPTY,
                    null, 0, false, null, null, null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    public static JobParameters copy(JobParameters params) {
        Parcel parcel = Parcel.obtain();
        try {
            params.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return JobParameters.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
}
