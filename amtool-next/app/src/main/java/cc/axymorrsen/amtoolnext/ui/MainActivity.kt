package cc.axymorrsen.amtoolnext.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.axymorrsen.amtoolnext.config.ConfigPublisher
import cc.axymorrsen.amtoolnext.config.HookConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SettingsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    var config by remember { mutableStateOf(ConfigPublisher.snapshot()) }

    fun update(block: (HookConfig) -> HookConfig) {
        ConfigPublisher.update(block)
        config = ConfigPublisher.snapshot()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AMTool Next") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(10.dp))
            Text("Apple Music 6.5.3 (1599)", style = MaterialTheme.typography.titleMedium)
            Text(
                "保留 Türkiye 账号、订阅和播放授权；只改变内容语言与 Apple 官方歌词语言候选。设置通过 libxposed API 102 热重载。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))

            ToggleRow("启用模块", "总开关", config.enabled) {
                update { it.copy(enabled = !it.enabled) }
            }
            ToggleRow("中文歌词优先", "请求 zh-Hans-CN / zh-Hans / zh-CN；本来就是中文歌词时直接使用中文原文", config.chineseLyrics) {
                update { it.copy(chineseLyrics = !it.chineseLyrics) }
            }
            ToggleRow("自动显示官方翻译", "非中文歌曲存在 Apple 官方中文翻译时优先启用", config.autoTranslation) {
                update { it.copy(autoTranslation = !it.autoTranslation) }
            }
            ToggleRow("发音 / 罗马音", "请求 zh-Latn / ja-Latn / ko-Latn", config.pronunciation) {
                update { it.copy(pronunciation = !it.pronunciation) }
            }
            ToggleRow("歌曲信息优先中文", "目录请求使用 zh-CN；无中文时保留 Apple 返回的英文/土区原名", config.chineseMetadata) {
                update { it.copy(chineseMetadata = !it.chineseMetadata) }
            }

            Spacer(Modifier.height(20.dp))
            Text("当前配置修订：${config.revision}", style = MaterialTheme.typography.labelMedium)
            Text(
                "热重载会主动刷新当前歌词页；不会修改 storefront、DSID、订阅资格、播放 URL 或 Apple Account 地区。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
