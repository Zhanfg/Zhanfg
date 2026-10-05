package io.github.andrealtb.coloroslyrics.provider.apple;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class AMToolCompatEntry extends XposedModule {
    private static final String TAG = "AppleProviderV3";
    private static final String TARGET = "com.apple.android.music";

    private final AtomicBoolean runtimeInstalled = new AtomicBoolean(false);

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;

        ClassLoader moduleLoader = AMToolCompatEntry.class.getClassLoader();
        ClassLoader hostLoader = param.getClassLoader();

        try {
            disableLegacyCoordinator(moduleLoader);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "legacy coordinator block failed", error);
        }

        Application current = currentApplication();
        if (current != null) {
            installRuntime(moduleLoader, hostLoader, current);
            return;
        }

        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object app = chain.getThisObject();
                        if (app instanceof Application) {
                            installRuntime(moduleLoader, hostLoader, (Application) app);
                        }
                        return result;
                    });
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Application.attach bootstrap failed", error);
        }
    }

    /**
     * The user's APK shell/settings/parser remain intact, but its old ApplePlayerHooker coordinator
     * is intentionally disabled. V3 owns the lifecycle so two independent state machines can never
     * race on MediaSession or lyricInfo.
     */
    private void disableLegacyCoordinator(ClassLoader moduleLoader) throws Exception {
        Class<?> type = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.apple.ApplePlayerHooker");
        Method onHook = type.getDeclaredMethod("onHook");
        onHook.setAccessible(true);
        hook(onHook)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> null);
        log(Log.INFO, TAG, "legacy ApplePlayerHooker coordinator disabled");
    }

    private void installRuntime(
            ClassLoader moduleLoader,
            ClassLoader hostLoader,
            Application application
    ) {
        if (!runtimeInstalled.compareAndSet(false, true)) return;
        try {
            new AppleProviderRuntimeV3(
                    this,
                    moduleLoader,
                    hostLoader,
                    application
            ).install();
        } catch (Throwable error) {
            runtimeInstalled.set(false);
            log(Log.ERROR, TAG, "provider runtime v3 installation failed", error);
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
