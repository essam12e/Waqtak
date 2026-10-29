package com.waqtak.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.waqtak.app.R
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SectionCard

/** Bibliographic data, verified against the publishers' records. Links are optional; the app never needs them. */
data class Reference(val citation: String, val url: String)

val REFERENCES = listOf(
    Reference(
        "Liu HY, Eso AA, Cook N, O'Neill HM, Albarqouni L. Meal Timing and Anthropometric and Metabolic Outcomes: " +
            "A Systematic Review and Meta-Analysis. JAMA Netw Open. 2024;7(11):e2442163. doi:10.1001/jamanetworkopen.2024.42163",
        "https://doi.org/10.1001/jamanetworkopen.2024.42163",
    ),
    Reference(
        "Liu D, Huang Y, Huang C, et al. Calorie Restriction with or without Time-Restricted Eating in Weight Loss. " +
            "N Engl J Med. 2022;386(16):1495-1504. doi:10.1056/NEJMoa2114833",
        "https://doi.org/10.1056/NEJMoa2114833",
    ),
    Reference(
        "Robinson E, Almiron-Roig E, Rutters F, de Graaf C, Forde CG, Tudur Smith C, Nolan SJ, Jebb SA. " +
            "A systematic review and meta-analysis examining the effect of eating rate on energy intake and hunger. " +
            "Am J Clin Nutr. 2014;100(1):123-151. doi:10.3945/ajcn.113.081745. PMID: 24847856",
        "https://pubmed.ncbi.nlm.nih.gov/24847856/",
    ),
    Reference(
        "Gardiner C, Weakley J, Burke LM, et al. The effect of caffeine on subsequent sleep: A systematic review and meta-analysis. " +
            "Sleep Med Rev. 2023;69:101764. doi:10.1016/j.smrv.2023.101764. PMID: 36870101",
        "https://pubmed.ncbi.nlm.nih.gov/36870101/",
    ),
    Reference("World Health Organization. Healthy diet (fact sheet).", "https://www.who.int/news-room/fact-sheets/detail/healthy-diet"),
    Reference("NHS. Water, drinks and hydration.", "https://www.nhs.uk/live-well/eat-well/food-guidelines-and-food-labels/water-drinks-nutrition/"),
    Reference(
        "National Institute of Diabetes and Digestive and Kidney Diseases (NIDDK). Eating, Diet, & Nutrition for GER & GERD.",
        "https://www.niddk.nih.gov/health-information/digestive-diseases/acid-reflux-ger-gerd-adults/eating-diet-nutrition",
    ),
)

@Composable
fun ScienceScreen() {
    val ctx = LocalContext.current
    ScreenColumn(stringResource(R.string.science_title)) {
        NoteText(stringResource(R.string.science_intro))
        SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
            SectionTitle(stringResource(R.string.science_supported_title))
            listOf(
                R.string.science_supported_1, R.string.science_supported_2, R.string.science_supported_3,
                R.string.science_supported_4, R.string.science_supported_5, R.string.science_supported_6,
            ).forEach { NoteText("• " + stringResource(it)) }
        }
        SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
            SectionTitle(stringResource(R.string.science_suggestion_title))
            listOf(
                R.string.science_suggestion_1, R.string.science_suggestion_2, R.string.science_suggestion_3,
                R.string.science_suggestion_4, R.string.science_suggestion_5,
            ).forEach { NoteText("• " + stringResource(it)) }
        }
        SectionCard {
            SectionTitle(stringResource(R.string.science_not_medical_title))
            NoteText(stringResource(R.string.science_not_medical))
        }
        SectionCard {
            SectionTitle(stringResource(R.string.privacy_title))
            NoteText(stringResource(R.string.privacy_full))
        }
        SectionCard {
            SectionTitle(stringResource(R.string.references_title))
            NoteText(stringResource(R.string.references_note))
            REFERENCES.forEach { r ->
                Text(r.citation, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(stringResource(R.string.open_source_link)) }
            }
        }
        SectionCard {
            SectionTitle(stringResource(R.string.licenses_title))
            NoteText(stringResource(R.string.licenses_text))
        }
    }
}
