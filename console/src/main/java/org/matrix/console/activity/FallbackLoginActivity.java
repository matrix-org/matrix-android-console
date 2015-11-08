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

import android.app.Activity;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;

import org.matrix.console.R;

import java.net.URLDecoder;
import java.util.HashMap;

public class FallbackLoginActivity extends Activity {
    public static String EXTRA_HOME_SERVER_ID = "org.matrix.console.activity.EXTRA_HOME_SERVER_ID";

    WebView mWebView = null;
    private String mHomeServerUrl = null;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // This is to fix a bug in the v7 support lib. If there is no options menu and you hit MENU, it will crash with a
            // NPE @ android.support.v7.app.ActionBarImplICS.getThemedContext(ActionBarImplICS.java:274)
            // This can safely be removed if we add in menu options on this screen
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)  {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_login_fallback);
        mWebView = (WebView) findViewById(R.id.account_creation_webview);
        mWebView.getSettings().setJavaScriptEnabled(true);


        Intent intent = getIntent();
        mHomeServerUrl = "https://matrix.org/";

        if (intent.hasExtra(EXTRA_HOME_SERVER_ID)) {
            mHomeServerUrl = intent.getStringExtra(EXTRA_HOME_SERVER_ID);
        }

        // check the trailing slash
        if (!mHomeServerUrl.endsWith("/")) {
            mHomeServerUrl += "/";
        }

        // AppRTC requires third party cookies to work
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();

        // clear the cookies must be cleared
        if ((null != cookieManager) && !cookieManager.hasCookies()) {
            launchWebView();
        } else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.removeAllCookie();
            launchWebView();
        } else {
            cookieManager.removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean value) {
                    launchWebView();
                }
            });
        }
    }

    private void launchWebView() {
        mWebView.loadUrl(mHomeServerUrl + "_matrix/static/client/login/");

        mWebView.setWebViewClient(new WebViewClient(){
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           SslError error) {
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);

                // on error case, close this activity
                FallbackLoginActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        FallbackLoginActivity.this.finish();
                    }
                });
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // avoid infinite onPageFinished call
                if (url.startsWith("http")) {
                    // Generic method to make a bridge between JS and the UIWebView
                    final String MXCJavascriptSendObjectMessage = "javascript:window.matrixLogin.sendObjectMessage = function(parameters) { var iframe = document.createElement('iframe');  iframe.setAttribute('src', 'js:' + JSON.stringify(parameters));  document.documentElement.appendChild(iframe); iframe.parentNode.removeChild(iframe); iframe = null; };";

                    view.loadUrl(MXCJavascriptSendObjectMessage);

                    // The function the fallback page calls when the registration is complete
                    final String MXCJavascriptOnRegistered = "javascript:window.matrixLogin.onLogin = function(homeserverUrl, userId, accessToken) { matrixLogin.sendObjectMessage({ 'action': 'onLogin', 'homeServer': homeserverUrl,'userId': userId,  'accessToken': accessToken  }); };";

                    view.loadUrl(MXCJavascriptOnRegistered);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view, java.lang.String url) {
                if ((null != url) &&  url.startsWith("js:")) {
                    String json = url.substring(3);
                    HashMap<String, Object> serverParams = null;

                    try {
                        // URL decode
                        json = URLDecoder.decode(json, "UTF-8");
                        serverParams = new Gson().fromJson(json, new TypeToken<HashMap<String, Object>>() {}.getType());

                    } catch (Exception e) {
                    }

                    // succeeds to parse parameters
                    if (null != serverParams) {
                        try {
                            String action = (String) serverParams.get("action");
                            LinkedTreeMap<String, String> parameters = (LinkedTreeMap<String, String>)serverParams.get("homeServer");

                            if (TextUtils.equals("onLogin", action) && (null != parameters)) {
                                final String userId = parameters.get("user_id");
                                final String accessToken = parameters.get("access_token");
                                final String homeServer = parameters.get("home_server");

                                // remove the trailing /
                                if (mHomeServerUrl.endsWith("/")) {
                                    mHomeServerUrl = mHomeServerUrl.substring(0, mHomeServerUrl.length() - 1);
                                }

                                // check if the parameters are defined
                                if ((null != homeServer) && (null != userId) && (null != accessToken)) {
                                    FallbackLoginActivity.this.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Intent returnIntent = new Intent();
                                            returnIntent.putExtra("homeServerUrl", mHomeServerUrl);
                                            returnIntent.putExtra("homeServer", homeServer);
                                            returnIntent.putExtra("userId", userId);
                                            returnIntent.putExtra("accessToken", accessToken);
                                            setResult(RESULT_OK, returnIntent);

                                            FallbackLoginActivity.this.finish();
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                        }
                    }
                    return true;
                }

                return false;
            }
        });
    }
}
