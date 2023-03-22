package com.likeminds.feedsx.utils

import android.annotation.SuppressLint
import android.app.Application
import android.provider.Settings
import com.likeminds.feedsx.utils.sharedpreferences.BasePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferences @Inject constructor(
    val application: Application
) : BasePreferences(USER_PREFS, application) {

    companion object {
        const val USER_PREFS = "user_prefs"
        const val MEMBER_ID = "member_id"
    }

    fun getMemberId(): Int {
        return getPreference(MEMBER_ID, -1)
    }

    fun saveMemberId(memberId: Int) {
        putPreference(MEMBER_ID, memberId)
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        return Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            ?: ""
    }
}