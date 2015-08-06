/*
 * Copyright 2015 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.console.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.text.TextUtils;
import android.util.Log;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.call.IMXCall;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.console.ConsoleApplication;
import org.matrix.console.Matrix;
import org.matrix.console.R;

public class CallViewActivity extends FragmentActivity {
    private static final String LOG_TAG = "CallViewActivity";

    public static final String EXTRA_MATRIX_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_MATRIX_ID";
    public static final String EXTRA_CALL_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_CALL_ID";

    private static CallViewActivity instance = null;

    private static View mSavedCallview = null;
    private static IMXCall mCall = null;

    private View mCallView;

    // account info
    private String mMatrixId = null;
    private MXSession mSession = null;
    private String mCallId = null;

    // call info
    private RoomMember mOtherMember = null;
    private Boolean mIsAnsweredElsewhere = false;

    // graphical items
    private View mAcceptRejectLayout;
    private Button mCancelButton;
    private Button mAcceptButton;
    private Button mRejectButton;
    private Button mStopButton;
    private ImageView mSpeakerSelectionView;
    private TextView mCallStateTextView;

    // sounds management
    private static MediaPlayer mRingingPLayer = null;
    private static MediaPlayer mCallEndPlayer = null;
    private static final int DEFAULT_PERCENT_VOLUME = 10;
    private static final int FIRST_PERCENT_VOLUME = 10;
    private static boolean firstCallAlert = true;
    private static int mCallVolume = 0;

    private IMXCall.MXCallListener mListener = new IMXCall.MXCallListener() {
        @Override
        public void onStateDidChange(String state) {
            mCallView.post(new Runnable() {
                @Override
                public void run() {
                    manageSubViews();
                }
            });
        }

        @Override
        public void onCallError(String error) {
            // display error message here
        }

        @Override
        public void onViewLoading(View callview) {
            mCallView = callview;
            mCallView.setBackgroundColor(Color.TRANSPARENT);
            insertCallView(mOtherMember.avatarUrl);
        }

        @Override
        public void onViewReady() {
            if (!mCall.isIncoming()) {
                mCall.placeCall();
            } else {
                mCall.launchIncomingCall();
            }
        }

        /**
         * The call was answered on another device
         */
        @Override
        public void onCallAnsweredElsewhere() {
            mIsAnsweredElsewhere = true;
            CallViewActivity.this.finish();
        }

        @Override
        public void onCallEnd() {
            CallViewActivity.this.finish();
        }
    };

    /**
     * @return true if the call can be resumed.
     * i.e this callView can be closed to be re opened later.
     */
    private static Boolean canCallBeResumed() {
        if (null != mCall) {
            String state = mCall.getCallState();

            // active call must be
            return state.equals(IMXCall.CALL_STATE_CONNECTING) ||
                    state.equals(IMXCall.CALL_STATE_CONNECTED) ||
                    state.equals(IMXCall.CALL_STATE_CREATE_ANSWER);
        }

        return false;
    }

    /**
     * @return the active call
     */
    public static IMXCall getActiveCall() {
        IMXCall res = mCall;

        // check if the call can be resume
        if (!canCallBeResumed()) {
            mCall = null;
            mSavedCallview = null;
        } else if (null != mCall) {
            // check if the call is still known
           if (mCall != mCall.getSession().mCallsManager.callWithCallId(mCall.getCallId())) {
               res = null;
           }
        }

        return res;
    }

    /**
     * @return the callViewActivity instance
     */
    public static CallViewActivity getInstance() {
        return instance;
    }

    /**
     * Insert the callView in the activity (above the other room member)
     * @param avatarUrl the other member avatar
     */
    private void insertCallView(String avatarUrl) {
        ImageView avatarView = (ImageView)CallViewActivity.this.findViewById(R.id.call_other_member);
        avatarView.setImageResource(R.drawable.ic_contact_picture_holo_light);

        if (!TextUtils.isEmpty(avatarUrl)) {
            int size = CallViewActivity.this.getResources().getDimensionPixelSize(R.dimen.member_list_avatar_size);
            mSession.getMediasCache().loadAvatarThumbnail(avatarView, avatarUrl, size);
        }

        RelativeLayout layout = (RelativeLayout)CallViewActivity.this.findViewById(R.id.call_layout);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
        layout.addView(mCallView, 1, params);

        mCallView.setVisibility(View.GONE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_callview);

        final Intent intent = getIntent();
        if (intent == null) {
            Log.e(LOG_TAG, "Need an intent to view.");
            finish();
            return;
        }

        if (!intent.hasExtra(EXTRA_MATRIX_ID)) {
            Log.e(LOG_TAG, "No matrix ID extra.");
            finish();
            return;
        }

        mCallId = intent.getStringExtra(EXTRA_CALL_ID);
        mMatrixId = intent.getStringExtra(EXTRA_MATRIX_ID);

        mSession = Matrix.getInstance(getApplicationContext()).getSession(mMatrixId);
        if (null == mSession) {
            Log.e(LOG_TAG, "invalid session");
            finish();
            return;
        }

        mCall = mSession.mCallsManager.callWithCallId(mCallId);

        if (null == mCall) {
            Log.e(LOG_TAG, "invalid callId");
            finish();
            return;
        }

        if (null == mRingingPLayer) {
            mRingingPLayer = MediaPlayer.create(this, R.raw.ring);
            mRingingPLayer.setLooping(true);
            mRingingPLayer.setVolume(1.0f, 1.0f);
        }

        if (null == mCallEndPlayer) {
            mCallEndPlayer = MediaPlayer.create(this, R.raw.callend);
            mCallEndPlayer.setLooping(false);
            mCallEndPlayer.setVolume(1.0f, 1.0f);
        }

        initMediaPlayerVolume();

        // assume that it is a 1:1 call.
        mOtherMember = mCall.getRoom().callees().get(0);

        // the webview has been saved after a screen rotation
        // getParent() != null : the static value have been reused whereas it should not
        if ((null != mSavedCallview) && (null == mSavedCallview.getParent())) {
            mCallView = mSavedCallview;
            insertCallView(mOtherMember.avatarUrl);
            manageSubViews();
        } else {
            mSavedCallview = null;
            // init the call button
            manageSubViews();
            mCall.createCallView();
        }

        mCall.addListener(mListener);

        instance = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // assume that the user cancels the call if it is ringing
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!canCallBeResumed()) {
                mCall.hangup("");
            } else {
                saveCallView();
            }
        } else if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) || (keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            // this is a trick to reduce the ring volume :
            // when the call is ringing, the AudioManager.Mode switch to MODE_IN_COMMUNICATION
            // so the volume is the next call one whereas the user expects to reduce the ring volume.
            if ((null != mCall) && mCall.getCallState().equals(IMXCall.CALL_STATE_RINGING)) {
                AudioManager audioManager = (AudioManager) CallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
                // IMXChrome call issue
                if (audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL), 0);
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void finish() {
        super.finish();
        instance = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConsoleApplication.setCurrentActivity(null);
        ((ConsoleApplication)getApplication()).startActivityTransitionTimer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConsoleApplication.setCurrentActivity(this);
        ((ConsoleApplication)getApplication()).stopActivityTransitionTimer();
    }

    private void refreshSpeakerButton() {
        if ((null != mCall) && mCall.getCallState().equals(IMXCall.CALL_STATE_CONNECTED)) {
            mSpeakerSelectionView.setVisibility(View.VISIBLE);

            AudioManager audioManager = (AudioManager) CallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager.isSpeakerphoneOn()) {
                mSpeakerSelectionView.setImageResource(R.drawable.ic_material_call);
            } else {
                mSpeakerSelectionView.setImageResource(R.drawable.ic_material_volume);
            }
        } else {
            mSpeakerSelectionView.setVisibility(View.GONE);
        }
    }

    /**
     * hangup the call.
     */
    private void onHangUp() {
        mSavedCallview = null;
        mCall.hangup("");
    }

    /**
     * Init the buttons layer
     */
    private void manageSubViews() {
        // initialize buttons
        if (null == mAcceptRejectLayout) {
            mAcceptRejectLayout = findViewById(R.id.layout_accept_reject);
            mAcceptButton = (Button) findViewById(R.id.accept_button);
            mRejectButton = (Button) findViewById(R.id.reject_button);
            mCancelButton = (Button) findViewById(R.id.cancel_button);
            mStopButton = (Button) findViewById(R.id.stop_button);
            mSpeakerSelectionView = (ImageView) findViewById(R.id.call_speaker_view);

            mCallStateTextView = (TextView) findViewById(R.id.call_state_text);

            mAcceptButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCall.answer();
                }
            });

            mRejectButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onHangUp();
                }
            });

            mCancelButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onHangUp();
                }
            });

            mStopButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onHangUp();
                }
            });

            mSpeakerSelectionView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AudioManager audioManager = (AudioManager)CallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);

                    // ignore speaker button if a bluetooth headset is connected
                    if (!audioManager.isBluetoothA2dpOn())
                    {
                        audioManager.setSpeakerphoneOn(!audioManager.isSpeakerphoneOn());
                        refreshSpeakerButton();
                    }
                }
            });

            mCallStateTextView.setText("");
        }

        String callState = mCall.getCallState();

        // hide / show avatar
        ImageView avatarView = (ImageView)CallViewActivity.this.findViewById(R.id.call_other_member);
        if (null != avatarView) {
            avatarView.setVisibility((callState.equals(IMXCall.CALL_STATE_CONNECTED) && mCall.isVideo()) ? View.GONE : View.VISIBLE);
        }

        refreshSpeakerButton();

        // display the button according to the call state
        if (callState.equals(IMXCall.CALL_STATE_ENDED)) {
            mAcceptRejectLayout.setVisibility(View.GONE);
            mCancelButton.setVisibility(View.GONE);
            mStopButton.setVisibility(View.GONE);
        } else if (callState.equals(IMXCall.CALL_STATE_CONNECTED)) {
            mAcceptRejectLayout.setVisibility(View.GONE);
            mCancelButton.setVisibility(View.GONE);
            mStopButton.setVisibility(View.VISIBLE);
        } else {
            mAcceptRejectLayout.setVisibility(mCall.isIncoming() ? View.VISIBLE : View.GONE);
            mCancelButton.setVisibility(mCall.isIncoming() ?  View.GONE : View.VISIBLE);
            mStopButton.setVisibility(View.GONE);
        }

        // display the callview only when the preview is displayed
        if (mCall.isVideo() && !callState.equals(IMXCall.CALL_STATE_ENDED)) {
            int visibility;

            if (callState.equals(IMXCall.CALL_STATE_WAIT_CREATE_OFFER) ||
                    callState.equals(IMXCall.CALL_STATE_INVITE_SENT) ||
                    callState.equals(IMXCall.CALL_STATE_RINGING) ||
                    callState.equals(IMXCall.CALL_STATE_CREATE_ANSWER) ||
                    callState.equals(IMXCall.CALL_STATE_CONNECTING) ||
                    callState.equals(IMXCall.CALL_STATE_CONNECTED)) {
                visibility = View.VISIBLE;
            } else {
                visibility = View.GONE;
            }

            if ((null != mCallView) && (visibility != mCallView.getVisibility())) {
                mCallView.setVisibility(visibility);
            }
        }

        // display the callstate
        if (callState.equals(IMXCall.CALL_STATE_CONNECTING) || callState.equals(IMXCall.CALL_STATE_CREATE_ANSWER)
                || callState.equals(IMXCall.CALL_STATE_WAIT_LOCAL_MEDIA) || callState.equals(IMXCall.CALL_STATE_WAIT_CREATE_OFFER)
                ) {
            //mAcceptButton.setAlpha(0.5f);
            //mAcceptButton.setEnabled(false);
            mCallStateTextView.setText(getResources().getString(R.string.call_connecting));
            mCallStateTextView.setVisibility(View.VISIBLE);
            stopRinging();
        } else if (callState.equals(IMXCall.CALL_STATE_CONNECTED)) {
            stopRinging();

            CallViewActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AudioManager audioManager = (AudioManager) CallViewActivity.this.getSystemService(Context.AUDIO_SERVICE);
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, mCallVolume, 0);
                    audioManager.setSpeakerphoneOn(mCall.isVideo());
                    refreshSpeakerButton();
                }
            });

            mCallStateTextView.setText(getResources().getString(R.string.call_connected));
            mCallStateTextView.setVisibility(mCall.isVideo() ? View.GONE : View.VISIBLE);
        } else if (callState.equals(IMXCall.CALL_STATE_ENDED)) {
            startEndCallSound();
            mCallStateTextView.setText(getResources().getString(R.string.call_ended));
            mCallStateTextView.setVisibility(View.VISIBLE);
        } else if (callState.equals(IMXCall.CALL_STATE_RINGING)) {
            startRinging();
            if (mCall.isIncoming()) {
                if (mCall.isVideo()) {
                    mCallStateTextView.setText(getResources().getString(R.string.incoming_video_call));
                } else {
                    mCallStateTextView.setText(getResources().getString(R.string.incoming_voice_call));
                }
            } else {
                mCallStateTextView.setText(getResources().getString(R.string.call_ring));
            }
            mCallStateTextView.setVisibility(View.VISIBLE);
        }
    }

    private void saveCallView() {
        if ((null != mCall) && !mCall.getCallState().equals(IMXCall.CALL_STATE_ENDED) && (null != mCallView) && (null != mCallView.getParent())) {
            ViewGroup parent = (ViewGroup) mCallView.getParent();
            parent.removeView(mCallView);
            mSavedCallview = mCallView;
            mCallView = null;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);
        saveCallView();
    }

    @Override
    public void onDestroy() {
        if (null != mCall) {
            mCall.removeListener(mListener);
        }
        super.onDestroy();
    }

    /**
     * Initialize the audio volume.
     */
    private void initMediaPlayerVolume() {
        AudioManager audioManager = (AudioManager)this.getSystemService(Context.AUDIO_SERVICE);

        // use the ringing volume to initialize the playing volume
        // it does not make sense to ring louder
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int minValue = firstCallAlert ? FIRST_PERCENT_VOLUME : DEFAULT_PERCENT_VOLUME;
        int ratio = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / maxVol;

        firstCallAlert = false;

        // ensure there is a minimum audio level
        // some users could complain they did not hear their device was ringing.
        if (ratio < minValue) {
            setMediaPlayerVolume(minValue);
        }
        else {
            setMediaPlayerVolume(ratio);
        }
    }

    private void setMediaPlayerVolume(int percent) {
        if(percent < 0 || percent > 100) {
            Log.e(LOG_TAG,"setMediaPlayerVolume percent is invalid: "+percent);
            return;
        }

        AudioManager audioManager = (AudioManager)this.getSystemService(Context.AUDIO_SERVICE);

        mCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);

        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if(maxVol > 0) {
            int volume = (int) ((float) percent / 100f * maxVol);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0);
        }
        Log.i(LOG_TAG, "Set media volume (ringback) to: " + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    /**
     * @return true if the ringing sound is played
     */
    private static Boolean isRinging() {
        if (null != mRingingPLayer) {
            return mRingingPLayer.isPlaying();
        }
        return false;
    }
    /**
     * Start the ringing sound
     */
    private static void startRinging() {
        if (null != mRingingPLayer) {
            // check if it is not yet playing
            if (!mRingingPLayer.isPlaying()) {
                // stop pending
                if ((null != mCallEndPlayer) && mCallEndPlayer.isPlaying()) {
                    mCallEndPlayer.stop();
                }
                mRingingPLayer.start();
            }
        }
    }

    /**
     * Stop the ringing sound
     */
    private static void stopRinging() {
        // sanity checks
        if ((null != mRingingPLayer) && mRingingPLayer.isPlaying()) {
            mRingingPLayer.pause();
        }
    }

    /**
     * Start the end call sound
     */
    private static void startEndCallSound() {
        // sanity checks
        if ((null != mCallEndPlayer) && !mCallEndPlayer.isPlaying()) {
            mCallEndPlayer.start();
        }
        stopRinging();
    }
}
