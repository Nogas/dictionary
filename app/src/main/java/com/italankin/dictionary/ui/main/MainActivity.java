/*
 * Copyright 2016 Igor Talankin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.italankin.dictionary.ui.main;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ShareCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.italankin.dictionary.App;
import com.italankin.dictionary.R;
import com.italankin.dictionary.dto.Language;
import com.italankin.dictionary.dto.Result;
import com.italankin.dictionary.dto.TranslationEx;
import com.italankin.dictionary.ui.PresenterFactory;
import com.italankin.dictionary.ui.settings.SettingsActivity;
import com.italankin.dictionary.ui.translation.TranslationActivity;
import com.italankin.dictionary.utils.SharedPrefs;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static android.widget.AdapterView.OnItemSelectedListener;

@SuppressLint("RestrictedApi")
public class MainActivity extends AppCompatActivity {

    private static final int SWITCH_ANIM_DURATION = 450;
    private static final int SWAP_LANGS_ANIM_DURATION = 300;
    private static final long PROGRESS_ANIM_DURATION = 300;
    private static final int TOOLBAR_ANIM_IN_DURATION = 600;
    private static final int SHARE_FAB_ANIM_DURATION = 300;

    private static final int REQUEST_CODE_SHARE = 17;

    private static final String KEY_PRESENTER_BUNDLE = "presenter_bundle";

    @Inject
    PresenterFactory presenterFactory;

    @Inject
    SharedPrefs prefs;

    @Inject
    InputMethodManager inputManager;

    @Inject
    ClipboardManager clipboardManager;

    private CoordinatorLayout mRoot;
    /**
     * EditText for text input
     */
    private EditText mInput;
    private View mInputLayout;
    /**
     * TextView in toolbar indicating current destination language
     */
    private Spinner mSpinnerSource;
    /**
     * TextView in toolbar indicating current source language
     */
    private Spinner mSpinnerDest;
    /**
     * Arrow in the toolbar for switching languages
     */
    private View mSwapLangs;
    private View mLookup;
    private TextView mTranscription;
    private View mToolbarInnerLayout;
    private RecyclerView mRecyclerView;
    private ProgressBar mProgressBar;
    private FloatingActionButton mShareFab;

    private MainPresenter mPresenter;
    private Bundle mPresenterBundle;
    private TranslationAdapter mRecyclerViewAdapter;

    ///////////////////////////////////////////////////////////////////////////
    // Activity callbacks
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        App.injector().inject(this);

        if (savedInstanceState != null) {
            mPresenterBundle = savedInstanceState.getBundle(KEY_PRESENTER_BUNDLE);
        } else {
            mPresenterBundle = new Bundle();
        }
        mPresenter = presenterFactory.getMainPresenter(mPresenterBundle);
        mPresenter.attach(this);

        setContentView(R.layout.activity_main);

        mRoot = findViewById(R.id.root);
        mInput = findViewById(R.id.edit_input);
        mInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                mRecyclerView.smoothScrollToPosition(0);
            }
        });
        mInputLayout = findViewById(R.id.input_card);
        mSpinnerSource = findViewById(R.id.spinner_lang_source);
        mSpinnerDest = findViewById(R.id.spinner_lang_dest);
        mSwapLangs = findViewById(R.id.swap_langs);
        mSwapLangs.setOnClickListener(view -> swapLanguages());
        mSwapLangs.setOnLongClickListener(view -> {
            Toast.makeText(this, R.string.toast_swap_langs, Toast.LENGTH_SHORT).show();
            return true;
        });
        mLookup = findViewById(R.id.lookup);
        mLookup.setOnClickListener(view -> startLookup());
        mTranscription = findViewById(R.id.text_transcription);
        mTranscription.setOnClickListener(view -> {
            Intent intent = ShareCompat.IntentBuilder
                    .from(this)
                    .setType("text/plain")
                    .setText(mTranscription.getText().toString())
                    .createChooserIntent();
            startActivity(intent);
        });
        mToolbarInnerLayout = findViewById(R.id.toolbar_inner_layout);
        mRecyclerView = findViewById(R.id.recycler_view);
        mProgressBar = findViewById(R.id.progress_bar);
        mShareFab = findViewById(R.id.btn_share);
        mShareFab.setOnClickListener(view -> shareLastResult());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setupInputLayout();
        setupRecyclerView();
        setControlsState(false);

        mPresenter.loadLanguages();
    }

    private void setupInputLayout() {
        mInputLayout.setOnClickListener(v -> mInput.requestFocus());
        ViewTreeObserver vto = mInputLayout.getViewTreeObserver();
        // add a global layout listener to update RecyclerView's offset
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver vto = mInputLayout.getViewTreeObserver();
                if (Build.VERSION.SDK_INT >= 16) {
                    vto.removeOnGlobalLayoutListener(this);
                } else {
                    vto.removeGlobalOnLayoutListener(this);
                }
                // Behavior which will offset input layout according to scroll events
                CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) mInputLayout.getLayoutParams();
                lp.setBehavior(new HidingViewBehavior(MainActivity.this, mInputLayout,
                        mRecyclerView, mInputLayout.getHeight()));
                mInputLayout.getParent().requestLayout();
                resetViewsState();
            }
        });

        mInput.setImeActionLabel(getString(R.string.lookup), EditorInfo.IME_ACTION_SEARCH);
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startLookup();
                return true;
            }
            return false;
        });
        mInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                resetViewsState();
            }
        });
    }

    private void setupRecyclerView() {
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        mRecyclerViewAdapter = new TranslationAdapter(this);
        mRecyclerViewAdapter.setHasStableIds(true);
        // on click listener
        mRecyclerViewAdapter.setListener(new TranslationAdapter.OnAdapterItemClickListener() {
            @Override
            public void onItemClick(int position) {
                Result result = mPresenter.getLastResult();
                TranslationEx item = result.translations.get(position);
                startActivity(TranslationActivity.getStartIntent(getApplicationContext(), item));
            }

            @Override
            public void onItemMenuClick(int position, int menuItemId) {
                ClipData clip = null;
                Result result = mPresenter.getLastResult();
                TranslationEx item = result.translations.get(position);
                switch (menuItemId) {
                    case R.id.action_lookup_word:
                        mInput.setText(item.text);
                        resetViewsState();
                        startLookup(item.text);
                        break;

                    case R.id.action_copy_mean:
                        clip = ClipData.newPlainText("means", item.means);
                        break;

                    case R.id.action_copy_synonyms:
                        clip = ClipData.newPlainText("synonyms", item.synonyms);
                        break;

                    case R.id.action_copy_translation:
                        clip = ClipData.newPlainText("translation", item.text);
                        break;
                }
                if (clip != null) {
                    clipboardManager.setPrimaryClip(clip);
                    Toast.makeText(MainActivity.this, R.string.msg_copied, Toast.LENGTH_SHORT).show();
                }
            }
        });

        mRecyclerView.setAdapter(mRecyclerViewAdapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        int fabVisibility = mShareFab.getVisibility();
        if (prefs.showShareFab()) {
            Result result = mPresenter.getLastResult();
            if (result != null && fabVisibility != View.VISIBLE) {
                showShareFab();
            }
        } else if (fabVisibility != View.GONE) {
            mShareFab.setVisibility(View.GONE);
            mRecyclerViewAdapter.showExtraSpace(false);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBundle(KEY_PRESENTER_BUNDLE, mPresenterBundle);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPresenter.saveLanguages();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPresenter.detach();
        if (isFinishing()) {
            mPresenter.clearSubscriptions();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /**
     * Called when a new {@link Intent} is received.
     *
     * @param intent new intent
     * @return {@code true}, if {@code intent} was successfully processed, {@code false} otherwise
     */
    private boolean handleIntent(Intent intent) {
        if (intent.getType() != null && intent.hasExtra(Intent.EXTRA_TEXT)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            int max = getResources().getInteger(R.integer.max_input_length);
            text = text.substring(0, Math.min(text.length(), max));
            if ("text/plain".equals(intent.getType()) && !TextUtils.isEmpty(text)) {
                intent.setType(null);
                mInput.setText(text);
                resetViewsState();
                startLookup(text);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SHARE && resultCode == RESULT_OK && prefs.closeOnShare()) {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (prefs.backFocusSearch()) {
            if (mInput.hasFocus()) {
                super.onBackPressed();
            } else {
                mInput.requestFocus();
            }
        } else {
            super.onBackPressed();
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Options menu
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_share:
                shareLastResult();
                return true;

            case R.id.action_history:
                showHistoryDialog();
                return true;

            case R.id.action_settings:
                startActivity(SettingsActivity.getStartIntent(this));
                return true;
        }

        return false;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Views state
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Enable or disable main controls.
     *
     * @param enabled new state
     */
    private void setControlsState(boolean enabled) {
        mInput.setEnabled(enabled);
        mLookup.setEnabled(enabled);
        mRecyclerView.setEnabled(enabled);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Dialogs
    ///////////////////////////////////////////////////////////////////////////

    private void showHistoryDialog() {
        final ArrayList<String> history = mPresenter.getHistory();
        if (history.isEmpty()) {
            Toast.makeText(this, R.string.msg_history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_history);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, history);
        builder.setAdapter(adapter, (dialog, which) -> {
            resetViewsState();
            startLookup(history.get(which));
        });
        builder.show();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Lookup
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Start lookup process.
     *
     * @param text text to lookup
     */
    private void startLookup(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        showProgressBar();
        inputManager.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
        mInput.clearFocus();
        mPresenter.lookup(text);
    }

    private void startLookup() {
        startLookup(mInput.getText().toString());
    }

    /**
     * Called from {@link MainPresenter}, when languages have been fetched from cache/net.
     *
     * @param languages   list of languages
     * @param destIndex   selected dest index
     * @param sourceIndex selected source index
     */
    public void onLanguagesResult(List<Language> languages, int destIndex, int sourceIndex) {
        final LanguageAdapter adapter = new LanguageAdapter(this, languages);
        adapter.setListener(Language::setFavorite);
        mSpinnerSource.setAdapter(adapter);
        mSpinnerSource.setSelection(sourceIndex);
        mSpinnerSource.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mPresenter.setSourceLanguage(position)) {
                    startLookup();
                }
                mPresenter.sortLanguages();
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mPresenter.sortLanguages();
                adapter.notifyDataSetChanged();
            }
        });
        mSpinnerDest.setAdapter(adapter);
        mSpinnerDest.setSelection(destIndex);
        mSpinnerDest.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mPresenter.setDestLanguage(position)) {
                    startLookup();
                }
                mPresenter.sortLanguages();
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mPresenter.sortLanguages();
                adapter.notifyDataSetChanged();
            }
        });

        mToolbarInnerLayout.setVisibility(View.VISIBLE);
        mToolbarInnerLayout.setTranslationY(-mToolbarInnerLayout.getHeight());
        mToolbarInnerLayout.setAlpha(0);
        mToolbarInnerLayout.animate()
                .alpha(1)
                .translationY(0)
                .setDuration(TOOLBAR_ANIM_IN_DURATION)
                .setInterpolator(new DecelerateInterpolator(2.5f))
                .start();

        setControlsState(true);

        // if we are not coming from share intent
        if (!handleIntent(getIntent())) {
            Result result = mPresenter.getLastResult();
            if (result != null) {
                onLookupResult(result);
            }
            if (mPresenter.isRequestInProgress()) {
                showProgressBar();
            } else if (mInput.getText().length() == 0) {
                mInput.requestFocus();
            } else {
                mInput.clearFocus();
            }
        }
    }

    private void swapLanguages() {
        if (!mPresenter.swapLanguages()) {
            return;
        }
        startLookup();

        // anim stuff

        float rotation = 180f;
        if (mSwapLangs.getRotation() > 0) {
            rotation *= -1;
        }
        mSwapLangs.setRotation(0);
        mSwapLangs.animate()
                .rotationBy(rotation)
                .setDuration(SWAP_LANGS_ANIM_DURATION)
                .start();

        mSpinnerDest.setEnabled(false);
        mSpinnerSource.setEnabled(false);
        SwitchAnimation anim = new SwitchAnimation(mSpinnerDest,
                -mSpinnerDest.getHeight(),
                0f,
                SWITCH_ANIM_DURATION,
                () -> {
                    OnItemSelectedListener listener = mSpinnerDest.getOnItemSelectedListener();
                    mSpinnerDest.setOnItemSelectedListener(null);
                    mSpinnerDest.setSelection(mPresenter.getDestLanguageIndex());
                    mSpinnerDest.setOnItemSelectedListener(listener);
                    listener = mSpinnerSource.getOnItemSelectedListener();
                    mSpinnerSource.setOnItemSelectedListener(null);
                    mSpinnerSource.setSelection(mPresenter.getSourceLanguageIndex());
                    mSpinnerSource.setOnItemSelectedListener(listener);
                    mSpinnerDest.setEnabled(true);
                    mSpinnerSource.setEnabled(true);
                });
        anim.start();
        anim = new SwitchAnimation(mSpinnerSource, mSpinnerSource.getHeight(), 0, SWITCH_ANIM_DURATION, null);
        anim.start();
    }

    /**
     * Called from {@link MainPresenter} when received lookup result from server
     *
     * @param result result object returned from server
     */
    public void onLookupResult(Result result) {
        List<TranslationEx> translations = result.translations;
        mInput.setText(result.text);
        mInput.clearFocus();
        if (!TextUtils.isEmpty(result.transcription)) {
            mTranscription.setText(String.format("[%s]", result.transcription));
        } else {
            mTranscription.setText("");
        }
        mRecyclerViewAdapter.setData(translations);
        mRecyclerView.scrollToPosition(0);
        if (mRecyclerView.getVisibility() != View.VISIBLE) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        hideProgressBar();
        if (prefs.showShareFab() && mShareFab.getVisibility() != View.VISIBLE) {
            showShareFab();
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Errors
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Called when error occured. Typically it's related to server or connection problems/wrong
     * request.
     *
     * @param messaage text to show
     */
    public void onError(String messaage) {
        if (messaage == null) {
            messaage = getString(R.string.error);
        }
        Snackbar snackbar = Snackbar.make(mInput, messaage, Snackbar.LENGTH_LONG);
        snackbar.setAction(android.R.string.ok, v -> {
            // empty callback for showing OK button
        });
        snackbar.show();
        hideProgressBar();
    }

    /**
     * Show error when no results were received.
     */
    public void onEmptyResult() {
        onError(getString(R.string.error_no_results));
    }

    /**
     * If error was occured while fetching languages.
     */
    public void onLanguagesError() {
        setControlsState(false);
        Snackbar snackbar = Snackbar.make(mRoot, R.string.error_langs, Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction(R.string.retry, v -> mPresenter.loadLanguages());
        snackbar.show();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Utility
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Reset translations applied by {@link HidingViewBehavior}.
     */
    private void resetViewsState() {
        mInputLayout.setTranslationY(0);
        mRecyclerView.setTranslationY(mInputLayout.getHeight());
        mRecyclerView.scrollToPosition(0);
    }

    /**
     * Share last result (currently displaying to user).
     */
    private void shareLastResult() {
        String[] result = mPresenter.getShareResult();
        if (result == null) {
            Toast.makeText(MainActivity.this, R.string.error_share, Toast.LENGTH_SHORT).show();
        } else {
            Intent intent = ShareCompat.IntentBuilder
                    .from(this)
                    .setType("text/plain")
                    .setSubject(result[0])
                    .setText(result[1])
                    .createChooserIntent();
            startActivityForResult(intent, REQUEST_CODE_SHARE);
        }
    }

    /**
     * Show the progress bar.
     */
    private void showProgressBar() {
        if (mProgressBar.getVisibility() == View.INVISIBLE) {
            mProgressBar.setAlpha(0);
            mProgressBar.setVisibility(View.VISIBLE);
        } else if (mProgressBar.getAlpha() == 1) {
            return;
        }
        mProgressBar.animate()
                .alpha(1)
                .setDuration(PROGRESS_ANIM_DURATION)
                .setListener(null)
                .start();
    }

    /**
     * Hide the progress bar.
     */
    private void hideProgressBar() {
        if (mProgressBar.getVisibility() == View.VISIBLE) {
            mProgressBar.animate()
                    .alpha(0)
                    .setDuration(PROGRESS_ANIM_DURATION)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mProgressBar.setVisibility(View.INVISIBLE);
                        }
                    })
                    .start();
        }
    }

    /**
     * Show share button.
     */
    private void showShareFab() {
        mRecyclerViewAdapter.showExtraSpace(true);
        mShareFab.setVisibility(View.VISIBLE);
        mShareFab.setScaleX(0);
        mShareFab.setScaleY(0);
        mShareFab.animate()
                .scaleX(1)
                .scaleY(1)
                .setDuration(SHARE_FAB_ANIM_DURATION)
                .setInterpolator(new DecelerateInterpolator(2))
                .start();
    }

}
