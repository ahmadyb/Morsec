package app.morsecode.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * The owners a screen composes under when a test launches no activity.
 *
 * Robolectric resolves an activity through the merged manifest, and only the debug
 * variant carries the one `androidx.compose.ui.test.manifest` declares, so the
 * screen tests launch nothing and supply what the screens actually read from an
 * activity instead: a resumed lifecycle, to collect state, and — where a screen
 * turns the system gesture into something of its own — a back dispatcher.
 */
internal class TestLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.RESUMED
    }
}

/** A [TestLifecycleOwner] that also owns a back dispatcher, for screens with a BackHandler. */
internal class TestBackOwner : OnBackPressedDispatcherOwner {

    private val registry = LifecycleRegistry(this)

    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()
    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.RESUMED
    }
}
