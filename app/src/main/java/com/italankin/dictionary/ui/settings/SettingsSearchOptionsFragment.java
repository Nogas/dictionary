package com.italankin.dictionary.ui.settings;

import android.os.Bundle;
import android.preference.PreferenceFragment;

import com.italankin.dictionary.R;

public class SettingsSearchOptionsFragment extends PreferenceFragment {

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);

        addPreferencesFromResource(R.xml.prefs_search);
    }

}