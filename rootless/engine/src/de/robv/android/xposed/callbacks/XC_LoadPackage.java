package de.robv.android.xposed.callbacks;

/**
 * Minimal XC_LoadPackage. The module only reads packageName, processName and
 * classLoader from the param (verified against its dex); the remaining official
 * fields are kept for shape compatibility.
 */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
