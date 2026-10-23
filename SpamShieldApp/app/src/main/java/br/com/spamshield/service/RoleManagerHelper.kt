package br.com.spamshield.service

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

object RoleManagerHelper {

    fun isCallScreeningRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun createCallScreeningRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
        return if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        } else {
            null
        }
    }
}
