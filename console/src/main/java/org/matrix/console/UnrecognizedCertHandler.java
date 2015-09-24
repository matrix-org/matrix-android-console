package org.matrix.console;


import android.app.Activity;
import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.matrix.androidsdk.HomeserverConnectionConfig;
import org.matrix.androidsdk.ssl.Fingerprint;

public class UnrecognizedCertHandler {
//    private Activity mActivity;
    private HomeserverConnectionConfig mHsConfig;
    private Fingerprint mFingerprint;
    private boolean mExisting;

    public UnrecognizedCertHandler(HomeserverConnectionConfig hsConfig, Fingerprint unrecognizedFingerprint, boolean existing) {
        mHsConfig = hsConfig;
        mFingerprint = unrecognizedFingerprint;
        mExisting = existing;
//        mActivity = ConsoleApplication.getInstance().getCurrentActivity();
    }

    public void show(final Callback callback) {
        Activity activity = ConsoleApplication.getInstance().getCurrentActivity();
        if (activity == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        LayoutInflater inflater = activity.getLayoutInflater();

        View layout = inflater.inflate(R.layout.ssl_fingerprint_prompt, null);

        TextView ssl_fingerprint_title = (TextView) layout.findViewById(R.id.ssl_fingerprint_title);
        ssl_fingerprint_title.setText(
                String.format(activity.getString(R.string.ssl_fingerprint_hash), mFingerprint.getType().toString())
        );

        TextView ssl_fingerprint = (TextView) layout.findViewById(R.id.ssl_fingerprint);
        ssl_fingerprint.setText(mFingerprint.getBytesAsHexString());

        builder.setView(layout);
        builder.setTitle(R.string.ssl_could_not_verify);

        builder.setPositiveButton(R.string.ssl_trust, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                mHsConfig.getAllowedFingerprints().add(mFingerprint);
                callback.onAccept();
            }
        });

        if (mExisting) {
            builder.setNeutralButton(R.string.ignore, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    callback.onIgnore();
                }
            });

            builder.setNegativeButton(R.string.action_logout, new DialogInterface.OnClickListener() {
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
