package com.ajctrl.sumiresync;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small, density-independent line icons used by the home screen. */
final class UiIcon extends Drawable {
    enum Kind { CLOUD, SLIDERS, FLAG, RIGHT, DOWN, CHECK, ERROR, SYNC, CLOCK }

    private final Kind kind;
    private final int color;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    UiIcon(Kind kind, int color, int size) {
        this.kind = kind;
        this.color = color;
        this.size = size;
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case FLAG:
                canvas.drawLine(4, 3, 4, 22, paint);
                Path flag = new Path();
                flag.moveTo(4, 4);
                flag.cubicTo(10, 0, 15, 8, 21, 4);
                flag.lineTo(21, 15);
                flag.cubicTo(15, 19, 10, 11, 4, 15);
                canvas.drawPath(flag, paint);
                break;
            case CLOUD:
                Path cloud = new Path();
                cloud.moveTo(6, 19);
                cloud.cubicTo(0, 19, 0, 10, 6, 10);
                cloud.cubicTo(6, 2, 17, 2, 18, 10);
                cloud.cubicTo(24, 10, 24, 19, 18, 19);
                cloud.close();
                canvas.drawPath(cloud, paint);
                break;
            case SLIDERS:
                for (int i = 0; i < 3; i++) {
                    float y = 5 + 7 * i;
                    float x = i == 1 ? 16 : 8;
                    canvas.drawLine(2, y, x - 2, y, paint);
                    canvas.drawLine(x + 2, y, 22, y, paint);
                    canvas.drawCircle(x, y, 2, paint);
                }
                break;
            case RIGHT: line(canvas, 9, 5, 16, 12, 9, 19); break;
            case DOWN: line(canvas, 5, 9, 12, 16, 19, 9); break;
            case CHECK:
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 12, 11, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.WHITE);
                paint.setAlpha(alpha);
                paint.setStrokeWidth(2.4f);
                line(canvas, 6.5f, 12, 10, 15.5f, 17.5f, 8);
                break;
            case ERROR:
                canvas.drawCircle(12, 12, 10, paint);
                canvas.drawLine(12, 6, 12, 13, paint);
                canvas.drawPoint(12, 17, paint);
                break;
            case SYNC:
                canvas.drawArc(4, 4, 20, 20, 205, 145, false, paint);
                canvas.drawArc(4, 4, 20, 20, 25, 145, false, paint);
                line(canvas, 16, 9, 20, 10, 21, 5);
                line(canvas, 8, 15, 4, 14, 3, 19);
                break;
            case CLOCK:
                canvas.drawCircle(12, 12, 10, paint);
                line(canvas, 12, 6, 12, 12, 16, 14);
                break;
        }
        canvas.restoreToCount(saved);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2, float x3, float y3) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        canvas.drawPath(path, paint);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
