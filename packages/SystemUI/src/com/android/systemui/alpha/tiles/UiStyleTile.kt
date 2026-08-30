/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.alpha.tiles

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.util.Log
import com.android.internal.logging.MetricsLogger
import com.android.internal.R as InternalR
import com.android.systemui.alpha.tiles.dialog.UiStyleDialogManager
import com.android.systemui.alpha.style.UiStyleRepository
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "UiStyleTile"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

class UiStyleTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val uiStyleRepository: UiStyleRepository,
    private val uiStyleDialogManager: UiStyleDialogManager,
    @Application private val scope: CoroutineScope,
) :
    QSTileImpl<QSTile.State>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {

    companion object {
        const val TILE_SPEC = "ui_style"

        private const val ALPHA_VISUALS_PACKAGE = "com.alpha.settings.ui"
        private const val ALPHA_VISUALS_MAIN = "com.alpha.settings.ui.MainActivity"
        private const val EXTRA_DESTINATION = "com.alpha.settings.ui.extra.DESTINATION"
        private const val DESTINATION_UI_STYLES = "ui_styles"
    }

    private var styleCollectionJob: Job? = null

    override fun newTileState(): QSTile.State = QSTile.State()

    override fun handleClick(expandable: Expandable?) {
        if (DEBUG) Log.d(TAG, "Tile clicked, opening dialog")
        mainHandler.post {
            uiStyleDialogManager.create(expandable)
        }
    }

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        val currentState = uiStyleRepository.styleState.value
        val styleId = currentState.styleId

        if (DEBUG) Log.d(TAG, "Updating tile state: $styleId v${currentState.themeVersion}")

        state.state = Tile.STATE_ACTIVE
        state.label = mContext.getString(InternalR.string.ui_style_tile_label)
        state.secondaryLabel = formatStyleName(styleId)
        state.icon = ResourceIcon.get(InternalR.drawable.ic_ui_style)

        // CRITICAL FIX: Embed themeVersion in contentDescription to force QSTileImpl to see a difference
        // and trigger a redraw when only settings (not style ID) change.
        state.contentDescription = "${state.label}: ${state.secondaryLabel} (v${currentState.themeVersion})"
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(InternalR.string.ui_style_tile_label)

    override fun getLongClickIntent(): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(ALPHA_VISUALS_PACKAGE, ALPHA_VISUALS_MAIN)
            putExtra(EXTRA_DESTINATION, DESTINATION_UI_STYLES)
        }

    override fun isAvailable(): Boolean = true

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            if (styleCollectionJob == null) {
                // Listen to full state to catch parameter changes
                styleCollectionJob = scope.launch {
                    uiStyleRepository.styleState.collectLatest {
                        refreshState()
                    }
                }
            }
        } else {
            if (styleCollectionJob != null) {
                styleCollectionJob?.cancel()
                styleCollectionJob = null
            }
        }
    }

    private fun formatStyleName(id: String): String {
        return when (id) {
            "system_default" -> mContext.getString(InternalR.string.ui_style_system_default)
            "outline" -> mContext.getString(InternalR.string.ui_style_outline)
            "neon" -> mContext.getString(InternalR.string.ui_style_neon)
            "bevel" -> mContext.getString(InternalR.string.ui_style_bevel)
            "gradient" -> mContext.getString(InternalR.string.ui_style_gradient)
            "reflective" -> mContext.getString(InternalR.string.ui_style_reflective)
            "slash" -> mContext.getString(InternalR.string.ui_style_slash)
            "aerogel" -> mContext.getString(InternalR.string.ui_style_aerogel)
            "metallic" -> mContext.getString(InternalR.string.ui_style_metallic)
            "lumen" -> mContext.getString(InternalR.string.ui_style_lumen)
            else -> id.replaceFirstChar { it.uppercase() }
        }
    }
}
