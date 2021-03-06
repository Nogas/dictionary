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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.Size;
import android.text.TextUtils;

import com.italankin.dictionary.BuildConfig;
import com.italankin.dictionary.R;
import com.italankin.dictionary.api.ApiClient;
import com.italankin.dictionary.dto.Definition;
import com.italankin.dictionary.dto.Language;
import com.italankin.dictionary.dto.Result;
import com.italankin.dictionary.utils.SharedPrefs;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import retrofit2.adapter.rxjava.HttpException;
import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

/**
 * Presenter for working with {@link MainActivity}.
 */
public class MainPresenter {

    private static final int LOOKUP_DEBOUNCE = 450;

    /**
     * Api client for making requests
     */
    private final ApiClient mClient;
    /**
     * Application shared preferences
     */
    private final SharedPrefs mPrefs;

    /**
     * Reference to attached activity
     */
    private WeakReference<MainActivity> mRef;

    /**
     * UI language for receiving results for this locale (if available)
     */
    private final String mUiLanguage;

    private List<Language> mLangs;

    private Language mSource;
    private Language mDest;

    /**
     * Languages load events.
     */
    private Subscription mSubLangs;

    /**
     * Lookup events subscription
     */
    private Subscription mSubLookup;

    /**
     * {@link Subject} for filtering input events.
     */
    private Subject<String, String> mEvents = PublishSubject.create();
    /**
     * A {@link Subscription} for handling emissions of {@link #mEvents}.
     */
    private Subscription mEventsSub;
    private Result mLastResult;
    private ArrayList<String> mHistory = new ArrayList<>(0);

    /**
     * Callback function called when receiving languages list.
     */
    private Action1<Object> onGetLangsResult = new Action1<Object>() {
        @Override
        public void call(Object o) {
            MainActivity a = mRef.get();
            if (a != null) {
                a.onLanguagesResult(mLangs, getDestLanguageIndex(), getSourceLanguageIndex());
            }
            mSubLangs = null;
        }
    };

    /**
     * Handling languages fetching errors.
     */
    private Action1<Throwable> mGetLangsErrorHandler = new Action1<Throwable>() {
        @Override
        public void call(Throwable throwable) {
            if (BuildConfig.DEBUG) {
                throwable.printStackTrace();
            }
            MainActivity a = mRef.get();
            if (a != null) {
                a.onLanguagesError();
            }
        }
    };

    public MainPresenter(ApiClient client, SharedPrefs prefs) {
        mClient = client;
        mPrefs = prefs;
        mUiLanguage = Locale.getDefault().getLanguage();
    }

    /**
     * @param activity activity for attaching presenter to
     */
    public void attach(MainActivity activity) {
        mRef = new WeakReference<>(activity);
        if (mEventsSub == null || mEventsSub.isUnsubscribed()) {
            mEventsSub = mEvents
                    .subscribeOn(Schedulers.computation())
                    .map(new Func1<String, String>() {
                        @Override
                        public String call(String s) {
                            return s.replaceAll("[^\\p{L}\\w -']", "").trim();
                        }
                    })
                    .filter(new Func1<String, Boolean>() {
                        @Override
                        public Boolean call(String s) {
                            return s != null && !s.isEmpty();
                        }
                    })
                    .debounce(LOOKUP_DEBOUNCE, TimeUnit.MILLISECONDS)
                    .subscribe(new Action1<String>() {
                        @Override
                        public void call(String s) {
                            lookupInternal(s);
                        }
                    });
        }
    }

    /**
     * Called when activity is finishing or recreating itself.
     */
    public void detach() {
        mRef.clear();
    }

    /**
     * Should be called when activity is finishing.
     */
    public void clearSubscriptions() {
        if (mSubLangs != null && !mSubLangs.isUnsubscribed()) {
            mSubLangs.unsubscribe();
            mSubLangs = null;
        }
        if (mSubLookup != null && !mSubLookup.isUnsubscribed()) {
            mSubLookup.unsubscribe();
            mSubLookup = null;
        }
        if (mEventsSub != null && !mEventsSub.isUnsubscribed()) {
            mEventsSub.unsubscribe();
            mEventsSub = null;
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Lookup
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Queue lookup request.
     *
     * @param text string to lookup
     */
    public void lookup(String text) {
        mEvents.onNext(text);
    }

    /**
     * Lookup text.
     *
     * @param text string to lookup
     */
    private void lookupInternal(final String text) {
        if (mSubLookup != null && !mSubLookup.isUnsubscribed()) {
            // cancel existing sent request
            mSubLookup.unsubscribe();
            mSubLookup = null;
        }

        @ApiClient.LookupFlags final int flags = mPrefs.getSearchFilter();

        mSubLookup = mClient.lookup(BuildConfig.API_KEY, getLangParam(false), text, mUiLanguage, flags)
                .flatMap(new Func1<List<Definition>, Observable<List<Definition>>>() {
                    @Override
                    public Observable<List<Definition>> call(List<Definition> definitions) {
                        if (definitions.isEmpty() && mPrefs.lookupReverse()) {
                            // if we got no result, attempt to lookup in reverse direction
                            //noinspection WrongConstant
                            return mClient.lookup(BuildConfig.API_KEY, getLangParam(true), text,
                                    mUiLanguage, flags);
                        }
                        return Observable.just(definitions);
                    }
                })
                .map(new Func1<List<Definition>, Result>() {
                    @Override
                    public Result call(List<Definition> definitions) {
                        if (definitions.isEmpty()) {
                            return null;
                        }
                        return new Result(definitions);
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        new Action1<Result>() {
                            @Override
                            public void call(Result result) {
                                if (result != null) {
                                    mLastResult = result;
                                    if (!mHistory.contains(result.text)) {
                                        mHistory.add(result.text);
                                    }
                                }
                                MainActivity a = mRef.get();
                                if (a != null) {
                                    if (result != null) {
                                        a.onLookupResult(result);
                                    } else {
                                        a.onEmptyResult();
                                    }
                                }
                            }
                        },
                        mErrorHandler
                );
    }

    /**
     * Concat languages codes for sending request to server.
     *
     * @param reverse false: SOURCE-DEST, true: DEST-SOURCE
     * @return language parameter
     */
    private String getLangParam(boolean reverse) {
        if (reverse) {
            return mDest.getCode() + "-" + mSource.getCode();
        } else {
            return mSource.getCode() + "-" + mDest.getCode();
        }
    }

    public Result getLastResult() {
        return mLastResult;
    }

    /**
     * Convert {@link Result} into "shareable" form.
     *
     * @return array of 2 strings, first is the text, second is the translations
     */
    @Size(2)
    @Nullable
    public String[] getShareResult() {
        if (mLastResult == null) {
            return null;
        }
        String[] result = new String[2];
        if (mPrefs.shareIncludeTranscription() && mLastResult.transcription != null &&
                !mLastResult.transcription.isEmpty()) {
            result[0] = mLastResult.text + " [" + mLastResult.transcription + "]";
        } else {
            result[0] = mLastResult.text;
        }
        result[1] = mLastResult.toString();
        return result;
    }

    public boolean isRequestInProgress() {
        return mSubLookup != null && !mSubLookup.isUnsubscribed();
    }

    public ArrayList<String> getHistory() {
        return mHistory;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Languages
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Load languages list. They will be loaded from net, if there are no cached files.
     */
    public void loadLanguages() {
        if (mLangs != null && mDest != null && mSource != null) {
            mRef.get().onLanguagesResult(mLangs, getDestLanguageIndex(), getSourceLanguageIndex());
            return;
        }

        if (mSubLangs != null && !mSubLangs.isUnsubscribed()) {
            // wait for request to finish
            return;
        }

        if (mPrefs.shouldUpdateLangs()) {
            mSubLangs = loadLanguagesFromRemote()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(onGetLangsResult, mGetLangsErrorHandler);
        } else {
            mSubLangs = mPrefs.getLanguagesList()
                    .map(new Func1<List<Language>, Object>() {
                        @Override
                        public Boolean call(List<Language> languages) {
                            updateLanguages(languages);
                            return true;
                        }
                    })
                    .onErrorResumeNext(new Func1<Throwable, Observable<?>>() {
                        @Override
                        public Observable<List<Language>> call(Throwable throwable) {
                            return loadLanguagesFromRemote();
                        }
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(onGetLangsResult, mGetLangsErrorHandler);
        }
    }

    @NonNull
    private Observable<List<Language>> loadLanguagesFromRemote() {
        return mClient.getLangs(BuildConfig.API_KEY)
                .doOnNext(new Action1<List<Language>>() {
                    @Override
                    public void call(List<Language> list) {
                        mPrefs.setLangsTimestamp(new Date());
                        updateLanguages(list);
                        saveLanguages();
                    }
                });
    }

    /**
     * Setup presenter fields for manipulating with languages.
     *
     * @param list list of the languages
     */
    private void updateLanguages(List<Language> list) {
        mLangs = list;
        if (!list.isEmpty()) {
            setSourceLanguageByCode(mPrefs.getSourceLang());
            setDestLanguageByCode(mPrefs.getDestLang());
            sortLanguages();
        }
    }

    public void sortLanguages() {
        Collections.sort(mLangs);
    }

    /**
     * Set source language by its position in languages list.
     *
     * @param position language index
     */
    public boolean setSourceLanguage(int position) {
        Language l = mLangs.get(position);
        if (mSource != l) {
            mSource = l;
            mPrefs.setSourceLang(mSource);
            return true;
        }
        return false;
    }

    /**
     * Set destination language by its position in languages list.
     *
     * @param position language index
     */
    public boolean setDestLanguage(int position) {
        Language l = mLangs.get(position);
        if (mDest != l) {
            mDest = l;
            mPrefs.setDestLang(mDest);
            return true;
        }
        return false;
    }

    /**
     * Set source language by its code.
     *
     * @param code language code
     */
    public void setSourceLanguageByCode(String code) {
        if (code == null) {
            code = Locale.getDefault().getLanguage();
        }
        mSource = mLangs.get(0);
        for (Language l : mLangs) {
            if (l.getCode().equals(code)) {
                mSource = l;
                break;
            }
        }
        mPrefs.setSourceLang(mSource);
    }


    /**
     * Set dest language by its code.
     *
     * @param code language code
     */
    public void setDestLanguageByCode(String code) {
        if (code == null) {
            code = Locale.getDefault().getLanguage();
        }
        mDest = mLangs.get(0);
        for (Language l : mLangs) {
            if (l.getCode().equals(code)) {
                mDest = l;
                break;
            }
        }
        mPrefs.setDestLang(mDest);
    }

    public int getSourceLanguageIndex() {
        return mLangs.indexOf(mSource);
    }

    public int getDestLanguageIndex() {
        return mLangs.indexOf(mDest);
    }

    /**
     * Swap languages.
     *
     * @return {@code true}, if languages were swapped, {@code false} otherwise
     * (ex. {@link #mSource} == {@link #mDest})
     */
    public boolean swapLanguages() {
        if (!TextUtils.equals(mSource.getCode(), mDest.getCode())) {
            Language tmp = mSource;
            mSource = mDest;
            mPrefs.setSourceLang(mSource);
            mDest = tmp;
            mPrefs.setDestLang(mDest);
            return true;
        }
        return false;
    }

    /**
     * Save language list on the disk.
     */
    public void saveLanguages() {
        mPrefs.saveLanguagesList(mLangs);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Error handlers
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Generic error handler.
     */
    private Action1<Throwable> mErrorHandler = new Action1<Throwable>() {
        @Override
        public void call(Throwable throwable) {
            if (BuildConfig.DEBUG) {
                throwable.printStackTrace();
            }
            MainActivity a = mRef.get();
            if (a != null) {
                String message = a.getString(R.string.error);
                if (throwable instanceof IOException) {
                    message = a.getString(R.string.error_no_connection);
                }
                if (throwable instanceof HttpException) {
                    HttpException e = (HttpException) throwable;
                    switch (e.code()) {
                        case 400:
                        case 501:
                            message = a.getString(R.string.error_lang_not_supported);
                            break;
                        case 401:
                        case 402:
                        case 403:
                        case 502:
                            // just error
                            break;
                        case 413:
                            message = a.getString(R.string.error_long_request);
                            break;
                        default:
                            message = a.getString(R.string.error_no_results);
                    }
                }
                a.onError(message);
            }
        }
    };

}
