package com.metaio.example_custom_renderer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.opengles.GL10;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.metaio.sdk.jni.ESCREEN_ROTATION;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
public class VideoRenderer {
	private static final String TAG = "VideoRenderer";
	private static int GL_TEXTURE_EXTERNAL_OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;

	private Context mContext;
	private int mTextureId;
	private MediaPlayer mMediaPlayer;
	private SurfaceTexture mSurfaceTexture;
	private FloatBuffer mVertexBuffer;
	private boolean mSurfaceUpdated;

	public VideoRenderer(Context context, GL10 gl, MediaPlayer mediaPlayer) {
		mContext = context;
		mMediaPlayer = mediaPlayer;

		final float[] vertices = {
				// 四角形
				-1, 1, 0, // 左上
				-1, -1, 0, // 左下
				1, 1, 0, // 右上
				1, -1, 0 // 右下
		};

		ByteBuffer buffer = ByteBuffer.allocateDirect(vertices.length * 4);
		buffer.order(ByteOrder.nativeOrder());
		mVertexBuffer = buffer.asFloatBuffer();
		mVertexBuffer.put(vertices);
		mVertexBuffer.rewind();

		// Generate texture
		int[] tmp = new int[1];
		gl.glGenTextures(1, tmp, 0);
		mTextureId = tmp[0];
		gl.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureId);
		gl.glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
		gl.glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);

		mSurfaceTexture = new SurfaceTexture(mTextureId);
		mSurfaceTexture
				.setOnFrameAvailableListener(new OnFrameAvailableListener() {
					@Override
					public void onFrameAvailable(SurfaceTexture surfaceTexture) {
						mSurfaceUpdated = true;
					}
				});

		Surface surface = new Surface(mSurfaceTexture);
		mMediaPlayer.setSurface(surface);
		surface.release();

		try {
			mMediaPlayer.prepare();
		} catch (IOException t) {
			Log.e(TAG, "media player prepare failed");
		}

	}

	public void draw(GL10 gl, ESCREEN_ROTATION screenRotation) {
		if (!mMediaPlayer.isPlaying())
			mMediaPlayer.start();
		
		synchronized (this) {
			if (mSurfaceUpdated) {
				mSurfaceTexture.updateTexImage();
				mSurfaceUpdated = false;
			}
		}

		gl.glEnable(GL_TEXTURE_EXTERNAL_OES);
		gl.glActiveTexture(GLES20.GL_TEXTURE0);
		gl.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureId);
		
		// テクスチャ
		float uv[] = { // ! u v
				0, 0, // 左上
				0, 1, // 左下
				1, 0, // 右上
				1, 1 // 右下
		};
		
		ByteBuffer buffer = ByteBuffer.allocateDirect(uv.length * 4);
		buffer.order(ByteOrder.nativeOrder());
		FloatBuffer fb = buffer.asFloatBuffer();
		fb.put(uv);
		fb.rewind();
		
		gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
		gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, fb);

		// 四角形を描画
		gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
		gl.glVertexPointer(3, GL10.GL_FLOAT, 0, mVertexBuffer);
//		gl.glColor4f(1, 0, 0, 1);
		gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4);

		// Disables
		gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
		gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
		gl.glDisable(GL_TEXTURE_EXTERNAL_OES);
	}
}
