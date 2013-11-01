package com.metaio.example_custom_renderer;

import static com.metaio.example_custom_renderer.GLES20Utils.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.opengles.GL10;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.metaio.sdk.jni.ESCREEN_ROTATION;
import com.metaio.sdk.jni.ImageStruct;

public final class CameraImageRenderer
{
    private static final String TAG = "CameraImageRenderer";

    /**
     * Camera frame aspect ratio (does not change with screen rotation, e.g.
     * 640/480 = 1.333)
     */
    private float mCameraAspect;

    private int mCameraImageHeight;

    private int mCameraImageWidth;

    private Context mContext;

    private boolean mInitialized = false;

    private boolean mMustUpdateTexture = false;

    /**
     * Value by which the X axis must be scaled in the overall projection matrix
     * in order to make up for a aspect-corrected (by cropping) camera image.
     * Set on each draw() call.
     */
    private float mScaleX;

    private float mScaleY;

    private ByteBuffer mTextureBuffer;

    private boolean mTextureInitialized = false;

    private int mTextureHeight;

    private int mTextureWidth;

    private FloatBuffer mTexCoordsBuffer;

    private FloatBuffer mVertexBuffer;

    private final String mVertexShader =
            "uniform mat4 uMatrix;" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMatrix * aPosition;\n" +
                    "  vTextureCoord = aTextureCoord;\n" +
                    "}\n";

    private final String mFragmentShader =
            "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private float[] mMatrix = new float[16];

    private int mProgram;
    private int mTextureID;
    private int muMatrixHandle;
    private int maPositionHandle;
    private int maTextureHandle;

    public CameraImageRenderer(Context context, GL10 gl)
    {
        mContext = context;

        final float[] vertices = {
                -1, -1, 0,
                1, -1, 0,
                -1, 1, 0,
                1, 1, 0
        };

        ByteBuffer buffer = ByteBuffer.allocateDirect(vertices.length * 4);
        buffer.order(ByteOrder.nativeOrder());
        mVertexBuffer = buffer.asFloatBuffer();
        mVertexBuffer.put(vertices);
        mVertexBuffer.rewind();

        // Create texture coordinates buffer but don't fill it yet
        buffer = ByteBuffer.allocateDirect(vertices.length / 3 * 8);
        buffer.order(ByteOrder.nativeOrder());
        mTexCoordsBuffer = buffer.asFloatBuffer();

        mProgram = createProgram(mVertexShader, mFragmentShader);
        if (mProgram == 0) {
            return;
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        checkGlError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }
        muMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix");
        if (muMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMatrix");
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID);
        checkGlError("glBindTexture mTextureID");

        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
    }

    public void draw(GL10 gl, ESCREEN_ROTATION screenRotation)
    {
        if (!mInitialized)
            return;

        // プログラムを指定
        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        // テクスチャの有効化
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID);

        // 頂点バッファの有効化と指定
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                0, mVertexBuffer);
        checkGlError("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGlError("glEnableVertexAttribArray maPositionHandle");

        // UVバッファの有効化と指定
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                0, mTexCoordsBuffer);
        checkGlError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        checkGlError("glEnableVertexAttribArray maTextureHandle");

        if (mMustUpdateTexture)
        {
            if (!mTextureInitialized)
            {
                // Allocate camera image texture once with 2^n dimensions
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_RGBA,
                        mTextureWidth,
                        mTextureHeight,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        null);

                mTextureInitialized = true;
            }

            // ...but only overwrite the camera image-sized region
            GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    mCameraImageWidth,
                    mCameraImageHeight,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    mTextureBuffer);

            final float xRatio = (float) mCameraImageWidth / mTextureWidth;
            final float yRatio = (float) mCameraImageHeight / mTextureHeight;

            final boolean cameraIsRotated = screenRotation == ESCREEN_ROTATION.ESCREEN_ROTATION_90 ||
                    screenRotation == ESCREEN_ROTATION.ESCREEN_ROTATION_270;
            final float cameraAspect = cameraIsRotated ? 1.0f / mCameraAspect : mCameraAspect;

            Display display = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getMetrics(displayMetrics);

            // DisplayMetrics.widthPixels/heightPixels are the width/height in
            // the current
            // orientation (i.e. values get swapped when you rotate the device)
            float screenAspect = (float) displayMetrics.widthPixels / displayMetrics.heightPixels;

            float offsetX, offsetY;

            if (cameraAspect > screenAspect)
            {
                // Camera image is wider (e.g. 480x640 camera image vs. a
                // 480x800 device, example
                // in portrait mode), so crop the width of the camera image
                float aspectRatio = screenAspect / cameraAspect;
                offsetX = 0.5f * (1 - aspectRatio);
                offsetY = 0;

                mScaleX = cameraAspect / screenAspect;
                mScaleY = 1;
            }
            else
            {
                // Screen is wider, so crop the height of the camera image
                float aspectRatio = cameraAspect / screenAspect;
                offsetY = 0.5f * (1 - aspectRatio);
                offsetX = 0;

                mScaleX = 1;
                mScaleY = screenAspect / cameraAspect;
            }

            if (cameraIsRotated)
            {
                // Camera image will be rendered with +-90� rotation, so switch
                // UV coordinates
                float tmp = offsetX;
                offsetX = offsetY;
                offsetY = tmp;
            }

            // Calculate texture coordinates. offsetX/offsetY are for cropping
            // if camera and screen
            // aspect ratios differ. xRatio/yRatio are here because the OpenGL
            // texture has
            // dimensions of 2^n, but the camera image does not fill it
            // completely (e.g. camera
            // image 640x480 vs. texture size 1024x512).
            mTexCoordsBuffer.put(new float[] {
                    offsetX * xRatio, (1 - offsetY) * yRatio,
                    (1 - offsetX) * xRatio, (1 - offsetY) * yRatio,
                    offsetX * xRatio, offsetY * yRatio,
                    (1 - offsetX) * xRatio, offsetY * yRatio
            });
            mTexCoordsBuffer.rewind();

            mMustUpdateTexture = false;
        }

        Matrix.setIdentityM(mMatrix, 0);
        switch (screenRotation) {
        // Portrait
            case ESCREEN_ROTATION_270:
                Matrix.rotateM(mMatrix, 0, -90, 0, 0, 1);
                break;

            // Reverse portrait (upside down)
            case ESCREEN_ROTATION_90:
                Matrix.rotateM(mMatrix, 0, 90, 0, 0, 1);
                break;

            // Landscape (right side of tall device facing up)
            case ESCREEN_ROTATION_0:
                break;

            // Reverse landscape (left side of tall device facing up)
            case ESCREEN_ROTATION_180:
                Matrix.rotateM(mMatrix, 0, 180, 0, 0, 1);
                break;

            default:
                Log.e(TAG, "Unknown screen rotation");
        }
        GLES20.glUniformMatrix4fv(muMatrixHandle, 1, false, mMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");
        GLES20.glFinish();
    }

    private static int getNextPowerOf2(int value)
    {
        for (int i = 0; i < 12; ++i)
        {
            if ((1 << i) >= value)
                return 1 << i;
        }

        throw new RuntimeException("Value too large");
    }

    public float getScaleX()
    {
        return mScaleX;
    }

    public float getScaleY()
    {
        return mScaleY;
    }

    private void init(int cameraImageWidth, int cameraImageHeight)
    {
        mTextureWidth = getNextPowerOf2(cameraImageWidth);
        mTextureHeight = getNextPowerOf2(cameraImageHeight);

        mTextureBuffer = ByteBuffer.allocateDirect(cameraImageWidth * cameraImageHeight * 4);

        mInitialized = true;
    }

    public void updateFrame(ImageStruct frame)
    {
        final int frameWidth = frame.getWidth();
        final int frameHeight = frame.getHeight();

        mCameraAspect = (float) frameWidth / frameHeight;

        switch (frame.getColorFormat())
        {
            case ECF_A8R8G8B8:
                if (!mInitialized)
                    init(frameWidth, frameHeight);

                if (!frame.getOriginIsUpperLeft())
                {
                    Log.e(TAG, "Unimplemented: ARGB upside-down");
                    return;
                }

                mTextureBuffer.rewind();
                frame.copyBufferToNioBuffer(mTextureBuffer);
                mTextureBuffer.rewind();

                break;

            default:
                Log.e(TAG, "Unimplemented color format " + frame.getColorFormat());
                return;
        }

        mMustUpdateTexture = true;

        mCameraImageWidth = frameWidth;
        mCameraImageHeight = frameHeight;
    }
}
