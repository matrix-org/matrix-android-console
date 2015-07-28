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

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.util.Log;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.squareup.okhttp.Call;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.console.Matrix;
import org.matrix.console.R;

import java.util.ArrayList;

public class CallViewActivity extends FragmentActivity {
    private static final String LOG_TAG = "CallViewActivity";

    public static final String VIDEO_CALL = "CallViewActivity.VIDEO_CALL";
    public static final String AUDIO_CALL = "CallViewActivity.AUDIO_CALL";

    public static final String EXTRA_MATRIX_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_MATRIX_ID";
    public static final String EXTRA_ROOM_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_ROOM_ID";
    public static final String EXTRA_CALLEE_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_CALLEE_ID";
    public static final String EXTRA_CALL_TYPE = "org.matrix.console.activity.CallViewActivity.EXTRA_CALL_TYPE";
    public static final String EXTRA_INIT_MSG = "org.matrix.console.activity.CallViewActivity.EXTRA_INIT_MSG";
    public static final String EXTRA_CALL_ID = "org.matrix.console.activity.CallViewActivity.EXTRA_CALL_ID";

    private static WebView mSavedWebview = null;
    private CallWebAppInterface mSavedWebAppInterface = null;

    private WebView mWebView;

    // account info
    private String mRoomId = null;
    private String mMatrixId = null;
    private MXSession mSession = null;
    private Room mRoom = null;

    private String mCalleeUserID = null;
    private String mCallType = null;
    private String mInitMsg = null;
    private String mCallId = null;

    private CallWebAppInterface mCallWebAppInterface;

    private static CallViewActivity instance = null;

    private static ArrayList<JsonElement> mPendingCandidates = new ArrayList<JsonElement>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (null != instance) {
            Log.e(LOG_TAG, "Cannot launch two call instances");
            finish();
            return;
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_callview);

        final Intent intent = getIntent();
        if (intent == null) {
            Log.e(LOG_TAG, "Need an intent to view.");
            finish();
            return;
        }

        if (!intent.hasExtra(EXTRA_ROOM_ID)) {
            Log.e(LOG_TAG, "No room ID extra.");
            finish();
            return;
        }

        if (!intent.hasExtra(EXTRA_MATRIX_ID)) {
            Log.e(LOG_TAG, "No matrix ID extra.");
            finish();
            return;
        }

        mRoomId = intent.getStringExtra(EXTRA_ROOM_ID);
        mMatrixId = intent.getStringExtra(EXTRA_MATRIX_ID);

        mSession = Matrix.getInstance(getApplicationContext()).getSession(mMatrixId);
        if (null == mSession) {
            Log.e(LOG_TAG, "invalid session");
            finish();
            return;
        }

        mRoom = mSession.getDataHandler().getRoom(mRoomId);
        if (null == mRoom) {
            Log.e(LOG_TAG, "invalid room");
            finish();
            return;
        }

        if (intent.hasExtra(EXTRA_CALLEE_ID)) {
            mCalleeUserID = intent.getStringExtra(EXTRA_CALLEE_ID);
        }

        if (intent.hasExtra(EXTRA_CALL_TYPE)) {
            mCallType = intent.getStringExtra(EXTRA_CALL_TYPE);
        }

        if (intent.hasExtra(EXTRA_INIT_MSG)) {
            mInitMsg = intent.getStringExtra(EXTRA_INIT_MSG);
        }

        if (intent.hasExtra(EXTRA_CALL_ID)) {
            mCallId = intent.getStringExtra(EXTRA_CALL_ID);
        }

        mWebView = (WebView)findViewById(R.id.webview);

        if (null != mSavedWebview) {
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            int index = parent.indexOfChild(mWebView);
            parent.removeView(mWebView);
            parent.addView(mSavedWebview, index);

            mWebView = mSavedWebview;
            mSavedWebview = null;
            mCallWebAppInterface = mSavedWebAppInterface;
            mSavedWebAppInterface = null;
        } else {

            mCallWebAppInterface = new CallWebAppInterface(this, mSession.getCredentials().accessToken, mRoom.getRoomId(), mCalleeUserID, mCallType, mInitMsg, mCallId);
            mWebView.addJavascriptInterface(mCallWebAppInterface, "Android");

            mWebView.setWebContentsDebuggingEnabled(true);
            WebSettings settings = mWebView.getSettings();

            // Enable Javascript
            settings.setJavaScriptEnabled(true);

            // Use WideViewport and Zoom out if there is no viewport defined
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);

            // Enable pinch to zoom without the zoom buttons
            settings.setBuiltInZoomControls(true);

            // Allow use of Local Storage
            settings.setDomStorageEnabled(true);

            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);


            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB) {
                // Hide the zoom controls for HONEYCOMB+
                settings.setDisplayZoomControls(false);
            }

            // Enable remote debugging via chrome://inspect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true);
            }

            mWebView.setWebViewClient(new WebViewClient());

            // AppRTC requires third party cookies to work
            android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
            cookieManager.setAcceptThirdPartyCookies(mWebView, true);

            final String url = "file:///android_asset/www/call.html";
            mWebView.loadUrl(url);

            mWebView.setWebChromeClient(new WebChromeClient() {

                @Override
                public void onPermissionRequest(final PermissionRequest request) {
                    CallViewActivity.this.runOnUiThread(new Runnable() {
                        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                        @Override
                        public void run() {
                            request.grant(request.getResources());
                        }
                    });
                }
            });
        }

        final Button callButton = (Button)findViewById(R.id.call_button);

        callButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //sendHangup();
                sendAccept();
            }
        });

        instance = this;
    }

    public static CallViewActivity getInstance() {
        return instance;
    }

    public static void addCandidate(JsonElement candidate) {
        if (null != instance) {
            instance.onNewCandidate(candidate);
        } else {
            synchronized (LOG_TAG) {
                mPendingCandidates.add(candidate);
            }
        }
    }

    public void checkPendingCandidates() {
        synchronized (LOG_TAG) {
            for(JsonElement candidate : mPendingCandidates) {
                onNewCandidate(candidate);
            }

            mPendingCandidates.clear();
        }
    }

    public void onCallAnswer(Event event) {
        mWebView.loadUrl("javascript:receivedAnswer(" + event.content.toString() + ")");
    }

    public void onCallHangup(Event event) {
        mWebView.loadUrl("javascript:onHangupReceived(" + event.content.toString() + ")");
        CallViewActivity.this.mWebView.post(new Runnable() {
            @Override
            public void run() {
                CallViewActivity.this.finish();
            }
        });
    }

    public void onNewCandidate(JsonElement candidate) {
        mWebView.loadUrl("javascript:gotRemoteCandidate(" + candidate.toString() + ")");
    }

    public void sendHangup() {
        mWebView.loadUrl("javascript:hangup()");
    }

    public void sendAccept() {
        mWebView.loadUrl("javascript:answerCall()");
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);

        ViewGroup parent = (ViewGroup) mWebView.getParent();
        parent.removeView(mWebView);

        mSavedWebview = mWebView;
        mSavedWebAppInterface = mCallWebAppInterface;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    private class CallWebAppInterface {
        Context mContext;
        String mAccessToKen;
        String mRoomId;
        String mUserId;
        String mCallType;
        String mInitMsg;
        String mCallId;

        CallWebAppInterface(Context context, String accessToken, String roomId, String userId, String callType, String initMsg, String callId)  {
            mContext = context;
            mAccessToKen = accessToken;
            mRoomId = roomId;
            mUserId = userId;
            mCallType = callType;
            mInitMsg = initMsg;
            mCallId = callId;
        }

        public void answerCall() {

        }

        @JavascriptInterface
        public String wgetAccessToken() {
            return mAccessToKen;
        }

        @JavascriptInterface
        public String wgetRoomId() {
            return mRoomId;
        }

        @JavascriptInterface
        public void wlog(String message) {
            Log.e(LOG_TAG, "WebView Message : " + message);
        }

        @JavascriptInterface
        public void wCallError(int code , String message) {
            Log.e(LOG_TAG, "WebView error Message : " + message);
        }

        @JavascriptInterface
        public void wEmit(String title , String message) {
            Toast.makeText(mContext, title + " : " + message , Toast.LENGTH_LONG).show();
        }

        @JavascriptInterface
        public String wgetUserId() {
            return mUserId;
        }

        @JavascriptInterface
        public void showToast(String toast)  {
            Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public void wOnLoaded() {
            mWebView.post(new Runnable() {
                @Override
                public void run() {
                    if (CallViewActivity.VIDEO_CALL.equals(mCallType)) {
                        mWebView.loadUrl("javascript:placeVideoCall()");
                    } else if (CallViewActivity.AUDIO_CALL.equals(mCallType)) {
                        mWebView.loadUrl("javascript:placeVoiceCall()");
                    } else if (null != mInitMsg) {
                        mWebView.loadUrl("javascript:initWithInvite('" + mCallId + "'," + mInitMsg.toString() + ")");

                        mWebView.post(new Runnable() {
                                          @Override
                                          public void run() {
                                              CallViewActivity.this.checkPendingCandidates();
                                          }
                                      });

                    }
                }
            });
        }

        @JavascriptInterface
        public void wSendEvent(final String roomId, final String eventType, final String jsonContent) {
            try {
                JsonObject content = (JsonObject) new JsonParser().parse(jsonContent);

                Toast.makeText(mContext, eventType, Toast.LENGTH_SHORT).show();

                Event event = new Event(eventType, content, mSession.getCredentials().userId, mRoom.getRoomId());
                mRoom.sendEvent(event, new ApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void info) {
                        if (eventType.equals(Event.EVENT_TYPE_CALL_HANGUP)) {
                            CallViewActivity.this.mWebView.post(new Runnable() {
                                @Override
                                public void run() {
                                    CallViewActivity.this.finish();
                                }
                            });
                        }
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                    }
                });

            } catch (Exception e) {
            }
        }
    }
}
