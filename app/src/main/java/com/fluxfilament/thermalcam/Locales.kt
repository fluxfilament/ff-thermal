/*
 * Copyright (C) 2026 Damirusnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.fluxfilament.thermalcam

import android.content.Context
import java.util.Locale

/**
 * The language the interface is actually showing, for formatting numbers and dates
 * to match it: "0,95" beside Russian text, "0.95" beside English. Hardcoding one
 * locale was fine while the app spoke only Russian; with a translation it would
 * leave decimal commas in the English interface.
 *
 * Read from a string resource rather than the system locale, because the two
 * differ: a phone set to en_DE shows the English strings but formats numbers the
 * German way, with a comma, and a French phone falls back to English strings with
 * French numbers. The resource is resolved by the same rules as the text around it.
 */
fun Context.uiLocale(): Locale = Locale.forLanguageTag(getString(R.string.number_locale))
