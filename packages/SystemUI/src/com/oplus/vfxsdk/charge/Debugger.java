/*
 * Copyright (C) 2026 The AlphaDroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oplus.vfxsdk.charge;

import android.util.Log;

/** Log helper. Tag prefix is part of the native charging-ring ABI. */
public final class Debugger {
    private Debugger() {}

    public static void i(String tag, String message) {
        Log.i("ChargingEffect_" + tag, message);
    }
}
