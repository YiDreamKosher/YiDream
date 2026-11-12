package com.yidream.mdm

import android.content.Context
import java.text.DateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

internal fun parseSiteWindowTime(value: String): Instant = OffsetDateTime.parse(value).toInstant()

internal fun formatSiteWindowTime(context: Context, time: Instant): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, context.resources.configuration.locales[0])
        .format(Date.from(time))
