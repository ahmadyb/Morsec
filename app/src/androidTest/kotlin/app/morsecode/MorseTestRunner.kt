package app.morsecode

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner declared in `app/build.gradle.kts`.
 *
 * Swapping in [HiltTestApplication] lets instrumented tests replace any binding
 * (a fake transport, an in-memory database) without touching production code.
 */
public class MorseTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
