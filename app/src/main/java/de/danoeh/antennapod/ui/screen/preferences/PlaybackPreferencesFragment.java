package de.danoeh.antennapod.ui.screen.preferences;

import android.app.Activity;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import de.danoeh.antennapod.ui.screen.feed.preferences.SkipPreferenceDialog;
import de.danoeh.antennapod.ui.screen.playback.VariableSpeedDialog;

import java.util.Map;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import androidx.preference.SwitchPreferenceCompat;

public class PlaybackPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String PREF_PLAYBACK_SPEED_LAUNCHER = "prefPlaybackSpeedLauncher";
    private static final String PREF_PLAYBACK_REWIND_DELTA_LAUNCHER = "prefPlaybackRewindDeltaLauncher";
    private static final String PREF_PLAYBACK_FAST_FORWARD_DELTA_LAUNCHER = "prefPlaybackFastForwardDeltaLauncher";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_playback);

        setupPlaybackScreen();
        buildSmartMarkAsPlayedPreference();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.playback_pref);
    }

    private void setupPlaybackScreen() {
        final Activity activity = getActivity();

        findPreference(PREF_PLAYBACK_SPEED_LAUNCHER).setOnPreferenceClickListener(preference -> {
            new VariableSpeedDialog().show(getChildFragmentManager(), null);
            return true;
        });
        findPreference(PREF_PLAYBACK_REWIND_DELTA_LAUNCHER).setOnPreferenceClickListener(preference -> {
            SkipPreferenceDialog.showSkipPreference(activity, SkipPreferenceDialog.SkipDirection.SKIP_REWIND, null);
            return true;
        });
        findPreference(PREF_PLAYBACK_FAST_FORWARD_DELTA_LAUNCHER).setOnPreferenceClickListener(preference -> {
            SkipPreferenceDialog.showSkipPreference(activity, SkipPreferenceDialog.SkipDirection.SKIP_FORWARD, null);
            return true;
        });
        if (Build.VERSION.SDK_INT >= 31) {
            findPreference(UserPreferences.PREF_UNPAUSE_ON_HEADSET_RECONNECT).setVisible(false);
            findPreference(UserPreferences.PREF_UNPAUSE_ON_BLUETOOTH_RECONNECT).setVisible(false);
        }

        buildEnqueueLocationPreference();
        SwitchPreferenceCompat prefFloatingTranscript = findPreference(UserPreferences.PREF_FLOATING_TRANSCRIPT);
        if (prefFloatingTranscript != null) {
            prefFloatingTranscript.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = Boolean.TRUE.equals(newValue);
                if (enabled) {
                    if (!Settings.canDrawOverlays(requireContext())) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                        return false;
                    }
                } else {
                    // ★ 关闭开关时立即停止悬浮窗
                    Intent stopIntent = new Intent();
                    stopIntent.setClassName(requireContext().getPackageName(),
                            "de.danoeh.antennapod.ui.transcript.TranscriptFloatingWindowService");
                    stopIntent.setAction("de.danoeh.antennapod.action.HIDE_FLOATING_TRANSCRIPT");
                    requireContext().startService(stopIntent);
                }
                UserPreferences.setFloatingTranscriptEnabled(enabled);
                return true;
            });
        }
    }
    @Override
    public void onResume() {
        super.onResume();
        SwitchPreferenceCompat prefFloatingTranscript = findPreference(UserPreferences.PREF_FLOATING_TRANSCRIPT);
        if (prefFloatingTranscript != null) {
            if (!Settings.canDrawOverlays(requireContext()) && prefFloatingTranscript.isChecked()) {
                prefFloatingTranscript.setChecked(false);
                UserPreferences.setFloatingTranscriptEnabled(false);
                // ★ 权限被撤销时也要停止
                Intent stopIntent = new Intent();
                stopIntent.setClassName(requireContext().getPackageName(),
                        "de.danoeh.antennapod.ui.transcript.TranscriptFloatingWindowService");
                stopIntent.setAction("de.danoeh.antennapod.action.HIDE_FLOATING_TRANSCRIPT");
                requireContext().startService(stopIntent);
            }
        }
    }
    private void buildEnqueueLocationPreference() {
        final Resources res = requireActivity().getResources();
        final Map<String, String> options = new ArrayMap<>();
        {
            String[] keys = res.getStringArray(R.array.enqueue_location_values);
            String[] values = res.getStringArray(R.array.enqueue_location_options);
            for (int i = 0; i < keys.length; i++) {
                options.put(keys[i], values[i]);
            }
        }

        ListPreference pref = requirePreference(UserPreferences.PREF_ENQUEUE_LOCATION);
        pref.setSummary(res.getString(R.string.pref_enqueue_location_sum, options.get(pref.getValue())));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!(newValue instanceof String)) {
                return false;
            }
            String newValStr = (String) newValue;
            pref.setSummary(res.getString(R.string.pref_enqueue_location_sum, options.get(newValStr)));
            return true;
        });
    }

    @NonNull
    private <T extends Preference> T requirePreference(@NonNull CharSequence key) {
        // Possibly put it to a common method in abstract base class
        T result = findPreference(key);
        if (result == null) {
            throw new IllegalArgumentException("Preference with key '" + key + "' is not found");

        }
        return result;
    }

    private void buildSmartMarkAsPlayedPreference() {
        final Resources res = getActivity().getResources();

        ListPreference pref = findPreference(UserPreferences.PREF_SMART_MARK_AS_PLAYED_SECS);
        String[] values = res.getStringArray(R.array.smart_mark_as_played_values);
        String[] entries = new String[values.length];
        for (int x = 0; x < values.length; x++) {
            if (x == 0) {
                entries[x] = res.getString(R.string.pref_smart_mark_as_played_disabled);
            } else {
                int v = Integer.parseInt(values[x]);
                if (v < 60) {
                    entries[x] = res.getQuantityString(R.plurals.time_seconds_quantified, v, v);
                } else {
                    v /= 60;
                    entries[x] = res.getQuantityString(R.plurals.time_minutes_quantified, v, v);
                }
            }
        }
        pref.setEntries(entries);
    }
}
