package com.waqtak.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.waqtak.app.R
import com.waqtak.app.ui.Routes
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard

/** Optional help: replay the first-run cards, check alarms, health notes, privacy and version. */
@Composable
fun HelpScreen(nav: NavHostController) {
    ScreenColumn(stringResource(R.string.help_title)) {
        SectionCard {
            SecondaryButton(stringResource(R.string.help_show_intro), { nav.navigate(Routes.INTRO) })
            SecondaryButton(stringResource(R.string.help_check_alarms), { nav.navigate(Routes.READINESS) })
            SecondaryButton(stringResource(R.string.help_health_info), { nav.navigate(Routes.SCIENCE) })
        }
        SectionCard {
            SectionTitle(stringResource(R.string.help_how_title))
            NoteText(stringResource(R.string.help_how_text))
        }
        SectionCard {
            SectionTitle(stringResource(R.string.privacy_title))
            NoteText(stringResource(R.string.privacy_short))
        }
        SectionCard {
            SectionTitle(stringResource(R.string.about_title))
            Text(stringResource(R.string.about_version, com.waqtak.app.BuildConfigInfo.VERSION))
        }
    }
}
