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
package com.italankin.dictionary.di.components;

import com.italankin.dictionary.di.modules.MainModule;
import com.italankin.dictionary.ui.main.MainActivity;

import javax.inject.Singleton;

import dagger.Component;

/**
 * Dagger component used for injection.
 */
@Singleton
@Component(modules = MainModule.class)
public interface Injector extends Presenters.Dependencies {

    void inject(MainActivity target);
}
