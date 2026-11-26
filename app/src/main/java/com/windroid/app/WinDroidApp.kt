package com.windroid.app

import android.app.Application

/**
 * Process-wide entry point for WinDroid.
 *
 * Intentionally self-contained: the service locator (container repository,
 * imagefs manager, session/runtime singletons) is wired here in Wave 2, so this
 * class never references types that do not exist yet and the scaffold always
 * compiles.
 */
class WinDroidApp : Application()
