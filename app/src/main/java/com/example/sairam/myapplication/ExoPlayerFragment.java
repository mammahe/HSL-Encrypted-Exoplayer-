package com.example.sairam.myapplication;

import android.media.MediaPlayer;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.os.Bundle;
import android.text.Layout;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import com.example.sairam.myapplication.exoplayer.DemoPlayer;
import com.example.sairam.myapplication.exoplayer.HlsRendererBuilder;
import com.google.android.exoplayer.AspectRatioFrameLayout;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.TimeRange;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.audio.AudioCapabilitiesReceiver;
import com.google.android.exoplayer.chunk.Format;

import java.util.ArrayList;

import butterknife.BindView;
import butterknife.ButterKnife;


public class ExoPlayerFragment extends Fragment {
    private View mView;
    private MediaController mediaController;
    private static final String TAG = ExoPlayerFragment.class.getSimpleName();
    public enum PlayerUiState {noStream, streamSoon, streaming, error, buffering, notAllowed}

    private DemoPlayer mPlayer;
    private boolean mNeedsPrepare;
    private String mUrl = "Your link which should be extended by .m3u8";
    private AudioCapabilitiesReceiver mAudioCapabilitiesReceiver;
    private AudioCapabilities mAudioCapabilities;

    @BindView(R.id.surface_view) SurfaceView mSurface;

    @BindView(R.id.video_frame) AspectRatioFrameLayout mVideoFrame;

    @BindView(R.id.playerInfoText) TextView mPlayerInfoText;


    @BindView(R.id.bufferingProgress) ProgressBar mBufferingProgress;

    public ExoPlayerFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAudioCapabilitiesReceiver = new AudioCapabilitiesReceiver(getActivity(), mAudioCapabilitiesListener);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mView =  inflater.inflate(R.layout.fragment_exoplayer, container, false);
        ButterKnife.bind(this, mView);
        View root =mView.findViewById(R.id.root);
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    toggleControlsVisibility();
                } else if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            }
        });
        root.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    return mediaController.dispatchKeyEvent(event);
                }
                return false;
            }
        });
        mediaController = new MediaController(getContext());
        mediaController.setAnchorView(root);
        return mView;
    }


    private void toggleControlsVisibility()  {
        if (mediaController.isShowing()) {
            mediaController.hide();
        } else {
            showControls();
        }
    }

    private void showControls() {
        mediaController.show(0);
    }
    @Override
    public void onResume() {
        super.onResume();
        mAudioCapabilitiesReceiver.register();
        preparePlayer();
    }

    @Override
    public void onPause() {
        super.onPause();
        mAudioCapabilitiesReceiver.unregister();
        releasePlayer();
    }


    private void preparePlayer() {
        if (mPlayer == null) {
            HlsRendererBuilder rendererBuilder = new HlsRendererBuilder(getActivity(), "USER_AGENT", mUrl, mAudioCapabilities);
            mPlayer = new DemoPlayer(rendererBuilder);
            mPlayer.addListener(mPlayerListener);
            mPlayer.setInfoListener(mInfoListener);
            mNeedsPrepare = true;
            mediaController.setMediaPlayer(mPlayer.getPlayerControl());
            mediaController.setEnabled(true);
        }
        if (mNeedsPrepare) {
            mPlayer.prepare();
            mNeedsPrepare = false;
        }
        mPlayer.setSurface(mSurface.getHolder().getSurface());
        mPlayer.setPlayWhenReady(true);
        mVideoFrame.setAspectRatio(1.6f);
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }


    // TODO: Find more information about audioCapabilites stuff.
    private AudioCapabilitiesReceiver.Listener mAudioCapabilitiesListener = new AudioCapabilitiesReceiver.Listener() {
        @Override
        public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
            boolean audioCapabilitiesChanged = !audioCapabilities.equals(mAudioCapabilities);
            if (mPlayer == null || audioCapabilitiesChanged) {
                mAudioCapabilities = audioCapabilities;
                releasePlayer();
                preparePlayer();
            } else if (mPlayer != null) {
                mPlayer.setBackgrounded(false);
            }
        }
    };

    private DemoPlayer.Listener mPlayerListener = new DemoPlayer.Listener() {
        @Override
        public void onStateChanged(boolean playWhenReady, int playbackState) {
            Log.d(TAG, "onStateChanged playWhenReady:" + playWhenReady + " playbackState:" + playbackState);
            //addDebugMessage("onStateChanged: state:" + playbackState + " playwhenready:" + playWhenReady, LogLevel.DEBUG);

            switch(playbackState) {
                case ExoPlayer.STATE_IDLE:
                    updatePlayerUi(PlayerUiState.noStream);
                    break;
                case ExoPlayer.STATE_PREPARING:
                    updatePlayerUi(PlayerUiState.buffering);
                    break;
                case ExoPlayer.STATE_BUFFERING:
                    updatePlayerUi(PlayerUiState.buffering);
                    break;
                case ExoPlayer.STATE_READY:
                    updatePlayerUi(PlayerUiState.streaming);
                    break;
                case ExoPlayer.STATE_ENDED:
                    updatePlayerUi(PlayerUiState.noStream);
                    break;
            }
        }


        private void updatePlayerUi(PlayerUiState playerUiState) {

            mPlayerInfoText.setVisibility(View.VISIBLE);
            mBufferingProgress.setVisibility(View.INVISIBLE);

            switch(playerUiState) {
                case noStream:
                    mPlayerInfoText.setText(R.string.livestream_status_no_stream);
                    break;
                case streamSoon:
                    mPlayerInfoText.setText(R.string.livestream_status_starting_soon);
                    break;
                case streaming:
                    mPlayerInfoText.setVisibility(View.GONE);
                    break;
                case buffering:
                    mPlayerInfoText.setText(R.string.livestream_status_buffering);
                    mBufferingProgress.setVisibility(View.VISIBLE);
                    break;
                case error:
                    mPlayerInfoText.setText(R.string.error_video_player_failed);
                    break;
                case notAllowed:
                    //FIXME
                    mPlayerInfoText.setText("NOT ALLOWED");
                    //mPlayerInfoText.setVisibility(View.GONE);
                    break;
            }

        }
        @Override
        public void onError(Exception e) {
            Log.e(TAG, "onError " + e.getMessage());
            updatePlayerUi(PlayerUiState.error);
          //  addDebugMessage("ERROR: " + e.getMessage() + ":" + e.toString(), LogLevel.ERROR);
        }

        @Override
        public void onVideoSizeChanged(int width, int height, float pixelWidthHeightRatio) {
            Log.d(TAG, "onVideoSizeChanged: width:" + width + ", height:" + height + ", ratio:" + pixelWidthHeightRatio);
            mVideoFrame.setAspectRatio(height == 0 ? 1 : (width * pixelWidthHeightRatio) / height);

        }
    };

    private DemoPlayer.InfoListener mInfoListener = new DemoPlayer.InfoListener() {
        @Override
        public void onVideoFormatEnabled(Format format, int trigger, int mediaTimeMs) {
            //addDebugMessage("videoFormat:" + format.toString(), LogLevel.DEBUG);
        }

        @Override
        public void onAudioFormatEnabled(Format format, int trigger, int mediaTimeMs) {
           // addDebugMessage("audioFormat:" + format.toString(), LogLevel.DEBUG);
        }

        @Override
        public void onDroppedFrames(int count, long elapsed) {
           // addDebugMessage("droppedFrames:" + count + ":elapsed:" + elapsed, LogLevel.VERBOSE);
        }

        @Override
        public void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate) {
           // addDebugMessage("onBandwidthSample:" + bytes + " bytes, bitrateEstimate:" + bitrateEstimate, LogLevel.DEBUG);
        }

        @Override
        public void onLoadStarted(int sourceId, long length, int type, int trigger, Format format, int mediaStartTimeMs, int mediaEndTimeMs) {
           // addDebugMessage("onLoadStarted: "+sourceId+":"+ (format!=null?format.bitrate:null)+" bps.", LogLevel.VERBOSE);
        }

        @Override
        public void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format, int mediaStartTimeMs, int mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs) {
           // addDebugMessage("onLoadCompleted: "+sourceId+":"+ (format!=null?format.bitrate:null)+" bps.", LogLevel.VERBOSE);
        }

        @Override
        public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs, long initializationDurationMs) {
           // addDebugMessage("onDecoderInitialized: " + decoderName + ", elapsedRealTime: " + elapsedRealtimeMs + ", initDurationMs: " +initializationDurationMs, LogLevel.VERBOSE);
        }

        @Override
        public void onSeekRangeChanged(TimeRange seekRange) {
            //addDebugMessage("onSeekRangeChanged: range:" + seekRange.toString(), LogLevel.VERBOSE);
        }
    };


    private enum LogLevel{ERROR, DEBUG, VERBOSE}

    private LogLevel mLogLevel = LogLevel.VERBOSE;

}
