package com.mfga.xposed;

import android.graphics.Typeface;
import android.os.Build;

/**
 * legacy / modern 两套入口共用的核心逻辑。
 *
 * 思路：不管目标 App 是通过 XML font-family(如 res/font/inter.xml)、
 * Compose 的 Font(R.font.xxx)，还是硬编码 createFromAsset/createFromFile
 * 加载字体，最终在 Android 12+ 上都会落到 android.graphics.Typeface 的
 * 几个静态工厂方法 / Typeface.Builder#build()。
 *
 * 因此只 hook 这一层：不管原本要生成什么字体，都换成系统默认字体，
 * 但保留原字体计算出来的 style/weight/italic,这样粗体、斜体语义不丢。
 *
 * 注意：Typeface.create(...) 内部在部分 Android 版本上也可能间接
 * 走回 Builder，为避免无限递归，用 ThreadLocal 做重入保护。
 */
public final class FontForceCore {

    private static final ThreadLocal<Boolean> IN_REPLACEMENT =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FontForceCore() {
    }

    /** 是否正处于"生成替换字体"的过程中，用来防止 hook 自我递归。 */
    public static boolean isReplacing() {
        return Boolean.TRUE.equals(IN_REPLACEMENT.get());
    }

    /**
     * 给定原本要被创建出来的自定义字体(可能为 null)，
     * 返回一个样式相同、但字形来自系统默认字体的 Typeface。
     */
    public static Typeface systemReplacementFor(Typeface original) {
        IN_REPLACEMENT.set(Boolean.TRUE);
        try {
            int style = original != null ? original.getStyle() : Typeface.NORMAL;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && original != null) {
                int weight = original.getWeight();
                boolean italic = original.isItalic();
                if (weight > 0) {
                    return Typeface.create(Typeface.DEFAULT, weight, italic);
                }
            }
            return Typeface.create(Typeface.DEFAULT, style);
        } catch (Throwable t) {
            // 任何异常都回退到最基础的系统字体，绝不能让 App 崩溃
            return Typeface.DEFAULT;
        } finally {
            IN_REPLACEMENT.set(Boolean.FALSE);
        }
    }
}
