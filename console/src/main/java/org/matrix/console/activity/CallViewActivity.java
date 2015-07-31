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

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.text.TextUtils;
import android.util.Log;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.call.IMXCall;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.console.Matrix;
import org.matrix.console.R;
import org.w3c.dom.Text;

import java.util.Collection;

public class CallViewActivity extends FragmentActivity {
    private static final String LOG_TAG = "CallViewActivity";

    public static final String EXTRA_MATRIX_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_MATRIX_ID";
    public static final String EXTRA_CALL_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_CALL_ID";

    private static View mSavedCallview = null;

    private View mCallView;

    // account info
    private String mMatrixId = null;
    private String mCallId = null;
    private MXSession mSession = null;

    // call info
    private IMXCall mCall = null;
    private RoomMember mOtherMember = null;

    // graphical items
    private View mAcceptRejectLayout;
    private Button mCancelButton;
    private Button mAcceptButton;
    private Button mRejectButton;
    private Button mStopButton;
    private TextView mCallStateTextView;

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

        @Override
        public void onCallEnd() {
            CallViewActivity.this.finish();
        }
    };

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

        Collection<RoomMember> members = mCall.getRoom().getMembers();

        // must only be called in 1:1 room
        if ((null == members) || (members.size() != 2)) {
            Log.e(LOG_TAG, "invalid members count");
            finish();
            return;
        }

        for(RoomMember m : members) {
            if (!mSession.getCredentials().userId.equals(m.getUserId())) {
                mOtherMember = m;
            }
        }

        // invalid member
        if (null == mOtherMember) {
            Log.e(LOG_TAG, "invalid member");
            finish();
            return;
        }

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
                    mSavedCallview = null;
                    mCall.hangup();
                    // some dedicated behaviour here ?
                }
            });

            mCancelButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mSavedCallview = null;
                    mCall.hangup();
                    // some dedicated behaviour here ?
                }
            });

            mStopButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mSavedCallview = null;
                    mCall.hangup();
                    // some dedicated behaviour here ?
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
            mAcceptButton.setAlpha(0.5f);
            mAcceptButton.setEnabled(false);
            mCallStateTextView.setText(getResources().getString(R.string.call_connecting));
            mCallStateTextView.setVisibility(View.VISIBLE);
        } else if (callState.equals(IMXCall.CALL_STATE_CONNECTED)) {
            mCallStateTextView.setText(getResources().getString(R.string.call_connected));
            mCallStateTextView.setVisibility(mCall.isVideo() ? View.GONE : View.VISIBLE);
        } else if (callState.equals(IMXCall.CALL_STATE_ENDED)) {
            mCallStateTextView.setText(getResources().getString(R.string.call_ended));
            mCallStateTextView.setVisibility(View.VISIBLE);
        } else if (callState.equals(IMXCall.CALL_STATE_RINGING)) {
            mCallStateTextView.setText(getResources().getString(R.string.call_ring));
            mCallStateTextView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);

        if ((null != mCall) && !mCall.getCallState().equals(IMXCall.CALL_STATE_ENDED)) {
            ViewGroup parent = (ViewGroup) mCallView.getParent();
            parent.removeView(mCallView);
            mSavedCallview = mCallView;
            mCallView = null;
        }
    }

    @Override
    public void onDestroy() {
        if (null != mCall) {
            mCall.removeListener(mListener);
        }
        super.onDestroy();
    }
}
