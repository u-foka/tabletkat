package org.exalm.tabletkat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;

import org.exalm.tabletkat.recent.TabletRecentsMod;
import org.exalm.tabletkat.settings.MultiPaneSettingsMod;
import org.exalm.tabletkat.statusbar.tablet.TabletStatusBarMod;

import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import static de.robv.android.xposed.XposedHelpers.*;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class TabletKatModule implements IXposedHookZygoteInit, IXposedHookLoadPackage, IXposedHookInitPackageResources {
    public static final String TAG = "TabletKatModule";
    public static final boolean DEBUG = true;

    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    public static final String SETTINGS_PACKAGE = "com.android.settings";

    private static TabletStatusBarMod statusBarMod;
    private static TabletRecentsMod recentsMod;
    private static MultiPaneSettingsMod settingsMod;

    public static Class mActivityManagerNativeClass;
    public static Class mBaseStatusBarClass;
    public static Class mBaseStatusBarHClass;
    public static Class mBatteryControllerClass;
    public static Class mBatteryMeterViewClass;
    public static Class mBluetoothControllerClass;
    public static Class mBrightnessControllerClass;
    public static Class mClockClass;
    public static Class mComAndroidInternalRDrawableClass;
    public static Class mComAndroidInternalRStyleClass;
    public static Class mDateViewClass;
    public static Class mDelegateViewHelperClass;
    public static Class mExpandHelperClass;
    public static Class mExpandHelperCallbackClass;
    public static Class mGlowPadViewClass;
    public static Class mKeyButtonViewClass;
    public static Class mLocationControllerClass;
    public static Class mNetworkControllerClass;
    public static Class mNotificationDataEntryClass;
    public static Class mNotificationRowLayoutClass;
    public static Class mPhoneStatusBarPolicyClass;
    public static Class mStatusBarIconClass;
    public static Class mStatusBarIconViewClass;
    public static Class mStatusBarManagerClass;
    public static Class mSystemUIClass;
    public static Class mToggleSliderClass;
    public static Class mTvStatusBarClass;
    public static Class mWindowManagerGlobalClass;
    public static Class mWindowManagerLayoutParamsClass;

    public static final String ACTION_PREFERENCE_CHANGED = "org.exalm.tabletkat.PREFERENCE_CHANGED";

    private static String MODULE_PATH = null;
    private static XSharedPreferences pref;
    private static boolean mIsTabletConfiguration = true;
    private static Boolean mHasNavigationBar;

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        MODULE_PATH = startupParam.modulePath;
        pref = new XSharedPreferences("org.exalm.tabletkat");
        pref.makeWorldReadable();

        Class c = findClass("com.android.internal.policy.impl.PhoneWindowManager", null);

        findAndHookMethod(c, "getNonDecorDisplayWidth", int.class, int.class, int.class, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                XposedBridge.log("shouldUseTabletUI: " + shouldUseTabletUI());
                setNavigationBarProperties(methodHookParam, shouldUseTabletUI());
                if (shouldUseTabletUI()) {
                    int fullWidth = (Integer) methodHookParam.args[0];
                    return fullWidth;
                }
                return invokeOriginalMethod(methodHookParam);
            }
        });
        findAndHookMethod(c, "getNonDecorDisplayHeight", int.class, int.class, int.class, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                setNavigationBarProperties(methodHookParam, shouldUseTabletUI());
                if (shouldUseTabletUI()){
                    int fullHeight = (Integer) methodHookParam.args[1];
                    int rotation = getIntField(methodHookParam.thisObject, "mSeascapeRotation");
                    int[] mNavigationBarHeightForRotation = (int[]) getObjectField(methodHookParam.thisObject, "mNavigationBarHeightForRotation");
                    return fullHeight - mNavigationBarHeightForRotation[rotation];
                }
                return invokeOriginalMethod(methodHookParam);
            }
        });
        findAndHookMethod(c, "getConfigDisplayHeight", int.class, int.class, int.class, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                if (shouldUseTabletUI()){
                    int fullWidth = (Integer) methodHookParam.args[0];
                    int fullHeight = (Integer) methodHookParam.args[1];
                    int rotation = (Integer) methodHookParam.args[2];
                    return callMethod(methodHookParam.thisObject, "getNonDecorDisplayHeight", fullWidth, fullHeight, rotation);
                }
                return invokeOriginalMethod(methodHookParam);
            }
        });

        XModuleResources res2 = XModuleResources.createInstance(MODULE_PATH, null);
        try {
            XResources.setSystemWideReplacement("android", "layout", "status_bar_latest_event_ticker", res2.fwd(R.layout.status_bar_latest_event_ticker));
            XResources.setSystemWideReplacement("android", "layout", "status_bar_latest_event_ticker_large_icon", res2.fwd(R.layout.status_bar_latest_event_ticker_large_icon));
        }catch (Resources.NotFoundException e){}
    }

    private void setNavigationBarProperties(XC_MethodHook.MethodHookParam param, boolean b) {
        int width = (Integer) param.args[0];
        int height = (Integer) param.args[1];
        int shortSize = width;
        if (width > height){
            shortSize = height;
        }
        Display d = (Display) getObjectField(param.thisObject, "mDisplay");
        if (d == null){
            return;
        }
        DisplayInfo info = new DisplayInfo();
        d.getDisplayInfo(info);
        int density = info.logicalDensityDpi;

        int shortSizeDp = shortSize * DisplayMetrics.DENSITY_DEFAULT / density;

        setBooleanField(param.thisObject, "mNavigationBarCanMove", shortSizeDp < 600 && !b);

        if (mHasNavigationBar == null) {
            mHasNavigationBar = getBooleanField(param.thisObject, "mHasNavigationBar");
        }
        setBooleanField(param.thisObject, "mHasNavigationBar", b || mHasNavigationBar);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals(SETTINGS_PACKAGE)){
            if (settingsMod == null){
                settingsMod = new MultiPaneSettingsMod();
            }
            if (isModEnabled("settings")) {
                settingsMod.addHooks(loadPackageParam.classLoader);
            }
            return;
        }
        if (!loadPackageParam.packageName.equals(SYSTEMUI_PACKAGE)){
            return;
        }
        debug("Loaded SystemUI");

        if (statusBarMod == null){
            statusBarMod = new TabletStatusBarMod();
        }
        if (recentsMod == null){
            recentsMod = new TabletRecentsMod();
        }
        mActivityManagerNativeClass = findClass("android.app.ActivityManagerNative", loadPackageParam.classLoader);
        mBaseStatusBarClass = findClass("com.android.systemui.statusbar.BaseStatusBar", loadPackageParam.classLoader);
        mBaseStatusBarHClass = findClass("com.android.systemui.statusbar.BaseStatusBar.H", loadPackageParam.classLoader);
        mBatteryControllerClass = findClass("com.android.systemui.statusbar.policy.BatteryController", loadPackageParam.classLoader);
        //Xperia custom battery meter
        try{
            Class c = findClass("com.sonymobile.systemui.statusbar.BatteryImage", loadPackageParam.classLoader);
            mBatteryMeterViewClass = c;
        }catch (ClassNotFoundError e){
            XposedBridge.log(e);
            //Ok, it's not Xperia
            mBatteryMeterViewClass = findClass("com.android.systemui.BatteryMeterView", loadPackageParam.classLoader);
        }
        mBluetoothControllerClass = findClass("com.android.systemui.statusbar.policy.BluetoothController", loadPackageParam.classLoader);
        mBrightnessControllerClass = findClass("com.android.systemui.settings.BrightnessController", loadPackageParam.classLoader);
        mClockClass = findClass("com.android.systemui.statusbar.policy.Clock", loadPackageParam.classLoader);
        mComAndroidInternalRDrawableClass = findClass("com.android.internal.R.drawable", loadPackageParam.classLoader);
        mComAndroidInternalRStyleClass = findClass("com.android.internal.R.style", loadPackageParam.classLoader);
        mDateViewClass = findClass("com.android.systemui.statusbar.policy.DateView", loadPackageParam.classLoader);
        mDelegateViewHelperClass = findClass("com.android.systemui.statusbar.DelegateViewHelper", loadPackageParam.classLoader);
        mExpandHelperClass = findClass("com.android.systemui.ExpandHelper", loadPackageParam.classLoader);
        mExpandHelperCallbackClass = findClass("com.android.systemui.ExpandHelper.Callback", loadPackageParam.classLoader);
        mGlowPadViewClass = findClass("com.android.internal.widget.multiwaveview.GlowPadView", loadPackageParam.classLoader);
        mKeyButtonViewClass = findClass("com.android.systemui.statusbar.policy.KeyButtonView", loadPackageParam.classLoader);
        mLocationControllerClass = findClass("com.android.systemui.statusbar.policy.LocationController", loadPackageParam.classLoader);
        mNetworkControllerClass = findClass("com.android.systemui.statusbar.policy.NetworkController", loadPackageParam.classLoader);
        mNotificationDataEntryClass = findClass("com.android.systemui.statusbar.NotificationData.Entry", loadPackageParam.classLoader);
        mNotificationRowLayoutClass = findClass("com.android.systemui.statusbar.policy.NotificationRowLayout", loadPackageParam.classLoader);
        mPhoneStatusBarPolicyClass = findClass("com.android.systemui.statusbar.phone.PhoneStatusBarPolicy", loadPackageParam.classLoader);
        mStatusBarIconClass = findClass("com.android.internal.statusbar.StatusBarIcon", loadPackageParam.classLoader);
        mStatusBarIconViewClass = findClass("com.android.systemui.statusbar.StatusBarIconView", loadPackageParam.classLoader);
        mStatusBarManagerClass = findClass("android.app.StatusBarManager", loadPackageParam.classLoader);
        mSystemUIClass = findClass("com.android.systemui.SystemUI", loadPackageParam.classLoader);
        mToggleSliderClass = findClass("com.android.systemui.settings.ToggleSlider", loadPackageParam.classLoader);
        mTvStatusBarClass = findClass("com.android.systemui.statusbar.tv.TvStatusBar", loadPackageParam.classLoader);
        mWindowManagerGlobalClass = findClass("android.view.WindowManagerGlobal", loadPackageParam.classLoader);
        mWindowManagerLayoutParamsClass = findClass("android.view.WindowManager.LayoutParams", loadPackageParam.classLoader);

        statusBarMod.addHooks(loadPackageParam.classLoader);
        if (isModEnabled("recents")) {
            recentsMod.addHooks(loadPackageParam.classLoader);
        }

        final Class systemBarsClass = findClass("com.android.systemui.statusbar.SystemBars", loadPackageParam.classLoader);
        findAndHookMethod(systemBarsClass, "createStatusBarFromConfig", new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                Object self = methodHookParam.thisObject;
                Context mContext = (Context) getObjectField(self, "mContext");
                Object mComponents = getObjectField(self, "mComponents");

                if (DEBUG) Log.d(TAG, "createStatusBarFromConfig");
                String clsName = "com.android.systemui.statusbar.tv.TvStatusBar";

                if (!shouldUseTabletUI() || clsName == null || clsName.length() == 0) {
                    clsName = "com.android.systemui.statusbar.phone.PhoneStatusBar";
                    setObjectField(self, "mStatusBar", createStatusBar(clsName, mContext, mComponents));
                    return null;
                }

                setObjectField(self, "mStatusBar", createStatusBar(clsName, mContext, mComponents));
                return null;
            }
        });

        findAndHookMethod(systemBarsClass, "onConfigurationChanged", Configuration.class, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                Configuration c = (Configuration) methodHookParam.args[0];
                Object self = methodHookParam.thisObject;
                Object mStatusBar = getObjectField(self, "mStatusBar");

                if (mStatusBar != null) {
                    boolean isTabletUI = mTvStatusBarClass.isInstance(mStatusBar);
                    boolean shouldUseTabletUI = shouldUseTabletUI();
                    mIsTabletConfiguration = true; //TODO: Add additional modes
                    if (isTabletUI != shouldUseTabletUI){
                        callMethod(self, "onServiceStartAttempt");
                        callMethod(self, "createStatusBarFromConfig");
                    }else {
                        callMethod(mStatusBar, "onConfigurationChanged", c);
                    }
                }
                return null;
            }
        });
    }

    private boolean shouldUseTabletUI() {
        pref.reload();
        return pref.getBoolean("enable_tablet_ui", true) && mIsTabletConfiguration;
    }

    private boolean isModEnabled(String id) {
        pref.reload();
        return pref.getBoolean("enable_mod_" + id, true);
    }

    private Object createStatusBar(String clsName, Context mContext, Object mComponents) {
        Class<?> cls;
        try {
            cls = mContext.getClassLoader().loadClass(clsName);
        } catch (Throwable t) {
            clsName = "com.android.systemui.statusbar.phone.PhoneStatusBar";
            return createStatusBar(clsName, mContext, mComponents);
        }
        Object mStatusBar;
        try {
            mStatusBar = cls.newInstance();
        } catch (Throwable t) {
            clsName = "com.android.systemui.statusbar.phone.PhoneStatusBar";
            return createStatusBar(clsName, mContext, mComponents);
        }
        setObjectField(mStatusBar, "mContext", mContext);
        setObjectField(mStatusBar, "mComponents", mComponents);
        statusBarMod.init(mStatusBar);
        if (mStatusBar != null) {
            callMethod(mStatusBar, "start");
            if (DEBUG) Log.d(TAG, "started " + mStatusBar.getClass().getSimpleName());
        }
        return mStatusBar;
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam initPackageResourcesParam) throws Throwable {
        if (initPackageResourcesParam.packageName.equals(SETTINGS_PACKAGE)){
            if (settingsMod == null){
                settingsMod = new MultiPaneSettingsMod();
            }
            final XResources res = initPackageResourcesParam.res;
            SystemR.init(res);

            XModuleResources res2 = XModuleResources.createInstance(MODULE_PATH, initPackageResourcesParam.res);

            TkR.init(res, res2);

            if (isModEnabled("settings")) {
                settingsMod.initResources(res, res2);
            }
            return;
        }
        if (!initPackageResourcesParam.packageName.equals(SYSTEMUI_PACKAGE)){
            return;
        }

        if (statusBarMod == null){
            statusBarMod = new TabletStatusBarMod();
        }
        if (recentsMod == null){
            recentsMod = new TabletRecentsMod();
        }
        final XResources res = initPackageResourcesParam.res;
        debug("Replacing SystemUI resources");
        SystemR.init(res);

        XModuleResources res2 = XModuleResources.createInstance(MODULE_PATH, initPackageResourcesParam.res);

        TkR.init(res, res2);

        statusBarMod.initResources(res, res2);
        if (isModEnabled("recents")) {
            recentsMod.initResources(res, res2);
        }
    }

    public static void debug(String s){
        if (!DEBUG){
            return;
        }
        XposedBridge.log(TAG + ": " + s);
    }

    public static BroadcastReceiver registerReceiver(Context c, final OnPreferenceChangedListener l){
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PREFERENCE_CHANGED);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String key = intent.getStringExtra("key");
                if (intent.hasExtra("boolValue")){
                    boolean boolValue = intent.getBooleanExtra("boolValue", false);
                    l.onPreferenceChanged(key, boolValue);
                }
                if (intent.hasExtra("intValue")){
                    int intValue = intent.getIntExtra("intValue", 0);
                    l.onPreferenceChanged(key, intValue);
                }
            }
        };
        pref.reload();
        l.init(pref);
        c.registerReceiver(r, f);
        return r;
    }

    public static Object invokeOriginalMethod(XC_MethodHook.MethodHookParam param) throws IllegalAccessException, InvocationTargetException{
        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
    }
}
