package com.terroroo.dobsongomenu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * <p>Invisible activity used to close the app completely.</p>
 *
 * <p>When it starts, it immediately finishes its task, which closes the app and removes it from the list of
 * recent apps. It is used when the mod cannot work, for example when a required permission is refused.</p>
 *
 * <p>Based on
 * <a href="https://stackoverflow.com/questions/22166282/close-application-and-remove-from-recent-apps">this
 * Stack Overflow answer</a>.</p>
 */
public final class ExitActivity extends Activity {
    /**
     * <p>Finishes the task as soon as the activity is created, which closes the app and removes it from the recent
     * apps.</p>
     *
     * @param savedInstanceState saved state of the activity (not used)
     */
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finishAndRemoveTask();
    }

    /**
     * <p>Closes the app after showing a message to the user and logging the reason as an error.</p>
     *
     * <p>{@link ExitActivity} is started in a new, cleared task, without animation and excluded from the recent
     * apps, so that it replaces everything else.</p>
     *
     * @param context context used to start the activity
     * @param toast   message shown to the user before closing
     * @param log     technical reason, written to Logcat as an error
     */
    public static void exitApplication(Context context, String toast, String log) {
        Utils.showToast(context, toast);
        Utils.Logcat(log, 2);
        Intent intent = new Intent(context, ExitActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }
}