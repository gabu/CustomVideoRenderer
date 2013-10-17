package com.metaio.example_custom_renderer;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.app.Activity;
import android.content.res.Configuration;
import android.hardware.Camera.CameraInfo;
import android.opengl.GLSurfaceView;
import android.opengl.GLSurfaceView.Renderer;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.metaio.sdk.MetaioDebug;
import com.metaio.sdk.SensorsComponentAndroid;
import com.metaio.sdk.jni.ERENDER_SYSTEM;
import com.metaio.sdk.jni.ESCREEN_ROTATION;
import com.metaio.sdk.jni.IMetaioSDKAndroid;
import com.metaio.sdk.jni.IMetaioSDKCallback;
import com.metaio.sdk.jni.ImageStruct;
import com.metaio.sdk.jni.MetaioSDK;
import com.metaio.sdk.jni.TrackingValues;
import com.metaio.tools.Screen;
import com.metaio.tools.SystemInfo;
import com.metaio.tools.io.AssetsManager;

public final class MainActivity extends Activity implements Renderer
{
	private static final String TAG = "MainActivity";

	static
	{
		IMetaioSDKAndroid.loadNativeLibs();
	}

	/**
	 * Defines whether the activity is currently paused
	 */
	private boolean mActivityIsPaused;
	
	/**
	 * Camera image renderer which takes care of differences in camera image and viewport
	 * aspect ratios
	 */
	private CameraImageRenderer mCameraImageRenderer;

	/**
	 * Simple cube that is rendered on top of the target pattern
	 */
	private Cube mCube;

	/**
	 * metaio SDK instance
	 */
	private IMetaioSDKAndroid mMetaioSDK;

	/** 
	 * Whether the metaio SDK null renderer is initialized
	 */
	private boolean mRendererInitialized;
	
	/**
	 * Current screen rotation
	 */
	private ESCREEN_ROTATION mScreenRotation;

	/**
	 * Sensors component
	 */
	private SensorsComponentAndroid mSensors;

	/**
	 * Main GLSufaceView in which everything is rendered
	 */
	private GLSurfaceView mSurfaceView;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		// Enable metaio SDK log messages based on build configuration
		MetaioDebug.enableLogging(BuildConfig.DEBUG);
		
		mCube = new Cube();
		mSurfaceView = null;
		mRendererInitialized = false;
		
		// Create metaio SDK instance by passing a valid signature
		final String signature = "QtbFIlF6fB1+ZmbspNCXx5slKpeLNHmIffPwMNhpFKc=";
		mMetaioSDK = MetaioSDK.CreateMetaioSDKAndroid(this, signature);
		
		mSensors = new SensorsComponentAndroid(getApplicationContext());
		mMetaioSDK.registerSensorsComponent(mSensors);
		
		mMetaioSDK.registerCallback(new IMetaioSDKCallback()
		{
			@Override
			public void onNewCameraFrame(ImageStruct cameraFrame)
			{
				if (mCameraImageRenderer != null)
					mCameraImageRenderer.updateFrame(cameraFrame);
			}

			@Override
			public void onSDKReady()
			{
				// Load desired tracking configuration when the SDK is ready
				final String trackingConfigFile = AssetsManager.getAssetPath("TrackingData_MarkerlessFast.xml");
				if (trackingConfigFile == null || !mMetaioSDK.setTrackingConfiguration(trackingConfigFile))
					Log.e(TAG, "Failed to set tracking configuration");
			}
		});

		try
		{
			// Extract all assets and overwrite existing files if debug build
			AssetsManager.extractAllAssets(getApplicationContext(), BuildConfig.DEBUG);
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

	}
	
	@Override
	protected void onPause()
	{
		super.onPause();

		if (mSurfaceView != null)
			mSurfaceView.onPause();
		
		mActivityIsPaused = true;
		mMetaioSDK.pause();
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		mMetaioSDK.resume();
		mActivityIsPaused = false;
		
		if (mSurfaceView != null)
		{
			if (mSurfaceView.getParent() == null)
			{
				FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
				addContentView(mSurfaceView, params);
				mSurfaceView.setZOrderMediaOverlay(true);
			}

			mSurfaceView.onResume();
		}
	}

	@Override
	protected void onStart()
	{
		super.onStart();
		
		if (mMetaioSDK != null)
		{
			// Set empty content view
			setContentView(new FrameLayout(this));
			
			final int cameraIndex = SystemInfo.getCameraIndex(CameraInfo.CAMERA_FACING_BACK);

			// Since the metaio SDK normally captures in YUV for performance reasons, we
			// enforce RGB capturing here to make it easier for us to handle the camera image.
			// Start camera only when the activity starts the first time
			// (see lifecycle: http://developer.android.com/training/basics/activity-lifecycle/pausing.html)
			if (!mActivityIsPaused)
				mMetaioSDK.startCamera(cameraIndex, 640, 480, 1, false); 

			// Create a new GLSurfaceView
			mSurfaceView = new GLSurfaceView(this);
			mSurfaceView.setEGLContextClientVersion(1);
			mSurfaceView.setRenderer(this);
			mSurfaceView.setKeepScreenOn(true);
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) 
	{
		updateScreenRotation();
		super.onConfigurationChanged(newConfig);
	}
	
	/**
	 * Update screen rotation
	 */
	private void updateScreenRotation()
	{
		mScreenRotation = Screen.getRotation(this);
		mMetaioSDK.setScreenRotation(mScreenRotation);
	}
	
	@Override
	protected void onStop()
	{
		super.onStop();

		// Remove GLSurfaceView from the hierarchy because it has been destroyed automatically
		if (mSurfaceView != null)
		{
			ViewGroup v = (ViewGroup) findViewById(android.R.id.content);
			v.removeAllViews();
			mSurfaceView = null;
		}
	}

	@Override
	protected void onDestroy()
	{
		super.onDestroy();

		if (mMetaioSDK != null)
		{
			mMetaioSDK.delete();
			mMetaioSDK = null;
		}

		if (mSensors != null)
		{
			mSensors.release();
			mSensors.registerCallback(null);
			mSensors.delete();
			mSensors = null;
		}
	}

	@Override
	public void onDrawFrame(GL10 gl)
	{
		mMetaioSDK.requestCameraImage();

		// Note: The metaio SDK itself does not render anything here because we initialized it with
		// the NULL renderer. This call is necessary to get the camera image and update tracking.
		mMetaioSDK.render();

		gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);

		gl.glDisable(GL10.GL_DEPTH_TEST);

		mCameraImageRenderer.draw(gl, mScreenRotation);

		gl.glEnable(GL10.GL_DEPTH_TEST);

		//
		// Render cube in front of camera image (if we're currently tracking)
		//

		final TrackingValues trackingValues = mMetaioSDK.getTrackingValues(1);

		if (trackingValues.isTrackingState())
		{
			float[] modelMatrix = new float[16];
			// preMultiplyWithStandardViewMatrix=false parameter explained below
			mMetaioSDK.getTrackingValues(1, modelMatrix, false, true);

			// With getTrackingValues(..., preMultiplyWithStandardViewMatrix=true), the metaio SDK
			// would calculate a model-view matrix, i.e. a standard look-at matrix (looking from the
			// origin along the negative Z axis) multiplied by the model matrix (tracking pose).
			// Here we use our own view matrix for demonstration purposes (parameter set to false),
			// for instance if you have your own camera implementation. Additionally, the cube is
			// scaled up by factor 40 and translated by 40 units in order to have its back face lie
			// on the tracked image.
			gl.glMatrixMode(GL10.GL_MODELVIEW);
			gl.glLoadIdentity();

			// Use typical view matrix (camera looking along negative Z axis, see previous hint)
			gl.glLoadIdentity();

			// The order is important here: We first want to scale the cube, then put it 40 units
			// higher (because it's rendered from -1 to +1 on all axes, after scaling that's +-40)
			// so that its back face lies on the tracked image and move it into place
			// (transformation to the coordinate system of the tracked image).
			gl.glMultMatrixf(modelMatrix, 0); // MODEL_VIEW = LOOK_AT * MODEL
			gl.glTranslatef(0, 0, 40);
			gl.glScalef(40, 40, 40); // all sides of the cube then have dimension 80

			gl.glMatrixMode(GL10.GL_PROJECTION);
			float[] projMatrix = new float[16];

			// Use right-handed projection matrix
			mMetaioSDK.getProjectionMatrix(projMatrix, true);

			// Since we render the camera image ourselves, and there are devices whose screen aspect
			// ratio does not match the camera aspect ratio, we have to make up for the stretched
			// and cropped camera image. The CameraImageRenderer class gives us values by which
			// pixels should be scaled from the middle of the screen (e.g. getScaleX() > 1 if the
			// camera image is wider than the screen and thus its width is displayed cropped).
			projMatrix[0] *= mCameraImageRenderer.getScaleX();
			projMatrix[5] *= mCameraImageRenderer.getScaleY();
			gl.glLoadMatrixf(projMatrix, 0);

			mCube.render(gl);
		}
	}



	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height)
	{
		if (height == 0)
			height = 1;

		gl.glViewport(0, 0, width, height);

		if (mMetaioSDK != null)
			mMetaioSDK.resizeRenderer(width, height);
	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config)
	{
		if (!mRendererInitialized)
		{
			mScreenRotation = Screen.getRotation(this);

			// Set up custom rendering (metaio SDK will only do tracking and not render any objects itself)
			mMetaioSDK.initializeRenderer(0, 0, mScreenRotation, ERENDER_SYSTEM.ERENDER_SYSTEM_NULL);
			mRendererInitialized = true;
		}
		
		// Create camera image renderer
		mCameraImageRenderer = new CameraImageRenderer(this, gl);

		gl.glShadeModel(GL10.GL_SMOOTH);
		gl.glClearColor(0, 0, 0, 0);

		gl.glClearDepthf(1.0f);
		gl.glDepthFunc(GL10.GL_LEQUAL);
		gl.glDisable(GL10.GL_LIGHTING);

		gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST);
	}
	
}
