package org.matrix.console;


import android.app.Activity;
import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.matrix.androidsdk.HomeserverConnectionConfig;
import org.matrix.androidsdk.ssl.Fingerprint;

import java.util.HashMap;
import java.util.HashSet;

public class UnrecognizedCertHandler {
    private static HashMap<String, HashSet<Fingerprint>> ignoredFingerprints = new HashMap<String, HashSet<Fingerprint>>();
    private static HashSet<String> openDialogs = new HashSet<String>();

    public static void show(final HomeserverConnectionConfig hsConfig, final Fingerprint unrecognizedFingerprint, boolean existing, final Callback callback) {
        Activity activity = ConsoleApplication.getInstance().getCurrentActivity();
        if (activity == null) return;

        if (hsConfig.getCredentials() != null) {
            HashSet<Fingerprint> f = ignoredFingerprints.get(hsConfig.getCredentials().userId);
            if (f != null && f.contains(unrecognizedFingerprint)) {
                callback.onIgnore();
                return;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        LayoutInflater inflater = activity.getLayoutInflater();

        View layout = inflater.inflate(R.layout.ssl_fingerprint_prompt, null);

        TextView ssl_fingerprint_title = (TextView) layout.findViewById(R.id.ssl_fingerprint_title);
        ssl_fingerprint_title.setText(
                String.format(activity.getString(R.string.ssl_fingerprint_hash), unrecognizedFingerprint.getType().toString())
        );

        TextView ssl_fingerprint = (TextView) layout.findViewById(R.id.ssl_fingerprint);
        ssl_fingerprint.setText(unrecognizedFingerprint.getBytesAsHexString());

        TextView ssl_user_id = (TextView) layout.findViewById(R.id.ssl_user_id);
        if (hsConfig.getCredentials() != null) {
            ssl_user_id.setText(
                 activity.getString(R.string.username) + ":  " + hsConfig.getCredentials().userId
            );
        } else {
            ssl_user_id.setText(
                    activity.getString(R.string.hs_url) + ":  " + hsConfig.getHomeserverUri().toString()
            );
        }

        TextView ssl_expl = (TextView) layout.findViewById(R.id.ssl_explanation);
        if (existing) {
            if (hsConfig.getAllowedFingerprints().size() > 0) {
                ssl_expl.setText(activity.getString(R.string.ssl_expected_existing_expl));
            } else {
                ssl_expl.setText(activity.getString(R.string.ssl_unexpected_existing_expl));
            }
        } else {
            ssl_expl.setText(activity.getString(R.string.ssl_cert_new_account_expl));
        }

        builder.setView(layout);
        builder.setTitle(R.string.ssl_could_not_verify);

        builder.setPositiveButton(R.string.ssl_trust, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                hsConfig.getAllowedFingerprints().add(unrecognizedFingerprint);
                callback.onAccept();
            }
        });

        if (existing) {
            builder.setNeutralButton(R.string.ssl_remain_offline, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    if (hsConfig.getCredentials() != null) {
                        HashSet<Fingerprint> f = ignoredFingerprints.get(hsConfig.getCredentials().userId);
                        if (f == null) {
                            f = new HashSet<Fingerprint>();
                            ignoredFingerprints.put(hsConfig.getCredentials().userId, f);
                        }

                        f.add(unrecognizedFingerprint);
                    }
                    callback.onIgnore();
                }
            });

            builder.setNegativeButton(R.string.ssl_logout_account, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    callback.onReject();
                }
            });
        } else {
            builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    callback.onReject();
                }
            });
        }

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    public interface Callback {
        void onAccept();
        void onIgnore();
        void onReject();
    }
}
