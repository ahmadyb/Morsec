package app.morsecode.di

import android.content.Context
import android.content.res.Resources
import app.morsecode.R
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.UnitLabels
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Singleton

/**
 * Locale aware formatting.
 *
 * The numeric rules live in core-model (pure JVM, unit tested); only the words
 * come from Android resources here, so nothing in the app is concatenated out of
 * English fragments and every label is translatable.
 */
@Module
@InstallIn(SingletonComponent::class)
public object AppModule {

    @Provides
    @Singleton
    public fun provideUnitLabels(@ApplicationContext context: Context): UnitLabels =
        AndroidUnitLabels(context.resources)

    /**
     * Unscoped on purpose: it captures the current locale, so a language change
     * produces a formatter with the new separators instead of a stale one.
     */
    @Provides
    public fun provideFormatters(units: UnitLabels): MorseFormatters =
        MorseFormatters(Locale.getDefault(), units)
}

internal class AndroidUnitLabels(private val resources: Resources) : UnitLabels {

    override val gb: String get() = resources.getString(R.string.unit_gb)
    override val mb: String get() = resources.getString(R.string.unit_mb)
    override val kb: String get() = resources.getString(R.string.unit_kb)
    override val mbPerSecond: String get() = resources.getString(R.string.unit_mb_per_second)
    override val percentSign: String get() = resources.getString(R.string.unit_percent)
    override val today: String get() = resources.getString(R.string.time_today)
    override val yesterday: String get() = resources.getString(R.string.time_yesterday)

    override fun justNow(): String = resources.getString(R.string.time_just_now)

    override fun minutesAgo(minutes: Long): String =
        resources.getString(R.string.time_minutes_ago, minutes.toInt())

    override fun hoursAgo(hours: Long): String =
        resources.getString(R.string.time_hours_ago, hours.toInt())

    override fun daysAgo(days: Long): String =
        resources.getQuantityString(R.plurals.time_days_ago, days.toInt().coerceAtLeast(1), days.toInt())
}
