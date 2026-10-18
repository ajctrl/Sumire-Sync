package com.ajctrl.sumiresync.sync;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowContentResolver;

final class ProviderTestSupport {
    static void registerTrusted(Context context, String authority, ContentProvider provider) throws Exception {
        PackageManager pm = context.getPackageManager();
        Signature signature = new Signature("11223344");
        PackageInfo own = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        own.signatures = new Signature[]{signature};
        Shadows.shadowOf(pm).installPackage(own);
        String packageName = SumireContract.packageIdForAuthority(authority);
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = packageName;
        pkg.signatures = new Signature[]{signature};
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = packageName;
        pkg.applicationInfo.enabled = true;
        ProviderInfo info = new ProviderInfo();
        info.name = packageName + ".ClipboardProvider";
        info.packageName = packageName;
        info.applicationInfo = pkg.applicationInfo;
        info.authority = authority;
        info.exported = true;
        info.enabled = true;
        pkg.providers = new ProviderInfo[]{info};
        Shadows.shadowOf(pm).installPackage(pkg);
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
    }
}
