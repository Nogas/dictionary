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
package com.italankin.dictionary;

import android.app.Application;

import com.italankin.dictionary.di.components.DaggerInjector;
import com.italankin.dictionary.di.components.DaggerPresenters;
import com.italankin.dictionary.di.components.Injector;
import com.italankin.dictionary.di.components.Presenters;
import com.italankin.dictionary.di.modules.MainModule;

public class App extends Application {

    private static Injector injector;
    private static Presenters presenters;

    @Override
    public void onCreate() {
        super.onCreate();

        MainModule mainModule = new MainModule(this);
        injector = DaggerInjector.builder()
                .mainModule(mainModule)
                .build();
        presenters = DaggerPresenters.builder()
                .dependencies(injector)
                .build();
    }

    public static Injector injector() {
        return injector;
    }

    public static Presenters presenters() {
        return presenters;
    }
}
