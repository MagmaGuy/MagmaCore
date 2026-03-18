package com.magmaguy.magmacore.util;

public class MathToolkit {
    /**
     * Linear interpolation
     */
    public static float lerp(float start, float end, float t) {
        return (1 - t) * start + t * end;
    }


    /**
     * Smooth interpolation using cubic easing (ease-in-out)
     */
    public static float smoothLerp(float start, float end, float t) {
        t = Math.max(0, Math.min(1, t));
        float smoothT = t * t * (3 - 2 * t);
        return (1 - smoothT) * start + smoothT * end;
    }

    /**
     * Cubic Bezier interpolation
     *
     * @param start Starting value
     * @param end   Ending value
     * @param t     Interpolation factor [0, 1]
     * @param cp1   Control point 1 influence
     * @param cp2   Control point 2 influence
     */
    public static float bezierLerp(float start, float end, float t, float cp1, float cp2) {
        t = Math.max(0, Math.min(1, t));

        float oneMinusT = 1 - t;
        float bezierT = 3 * oneMinusT * oneMinusT * t * cp1 +
                3 * oneMinusT * t * t * cp2 +
                t * t * t;

        return (1 - bezierT) * start + bezierT * end;
    }

    /**
     * Step interpolation - no interpolation, jumps at threshold
     *
     * @param start     Starting value
     * @param end       Ending value
     * @param t         Interpolation factor [0, 1]
     * @param threshold When to jump from start to end
     */
    public static float stepLerp(float start, float end, float t, float threshold) {
        return t < threshold ? start : end;
    }

    /**
     * Step interpolation with middle threshold
     */
    public static float stepLerp(float start, float end, float t) {
        return stepLerp(start, end, t, 0.5f);
    }
}
