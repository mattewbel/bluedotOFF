package app.bluedotoff

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import app.bluedotoff.core.*

@Composable fun GuideCard(selection:GuideSelection){
    val c=GuideContent.load(LocalContext.current,selection)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("${selection.step.number}  ${c.title}",style=MaterialTheme.typography.titleMedium)
        AndroidView(factory={GuideArtwork(it,selection)},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=304.dp))
        Text(c.body,style=MaterialTheme.typography.bodyMedium)
    }
}
