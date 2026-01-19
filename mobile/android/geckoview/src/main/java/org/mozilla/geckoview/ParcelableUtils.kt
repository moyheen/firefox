/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * vim: ts=4 sw=4 expandtab:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.geckoview

import android.os.Parcel

internal object ParcelableUtils {
    @JvmStatic
    fun writeBoolean(out: Parcel, `val`: Boolean) {
        out.writeByte((if (`val`) 1 else 0).toByte())
    }

    @JvmStatic
    fun readBoolean(source: Parcel): Boolean {
        return source.readByte().toInt() == 1
    }
}
