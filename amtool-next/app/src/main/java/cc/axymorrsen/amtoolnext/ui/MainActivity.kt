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
            Text("Apple Music 6.5.3 (1599) · alpha3-hotfix1", style = MaterialTheme.typography.titleMedium)
            Text(
                "保留 Türkiye 账号、订阅与播放授权。歌词始终走账号真实 storefront；歌曲/专辑展示信息改从中国大陆目录请求简体中文。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))

            ToggleRow("启用模块", "总开关", config.enabled) {
                update { it.copy(enabled = !it.enabled) }
            }
            ToggleRow("中文原歌词优先", "请求 zh-Hans-CN / zh-Hans / zh-CN", config.chineseLyrics) {
                update { it.copy(chineseLyrics = !it.chineseLyrics) }
            }
            ToggleRow("自动显示官方翻译", "修正 Apple 的 zh-Hans ↔ zh-Hans-CN 可用性判断", config.autoTranslation) {
                update { it.copy(autoTranslation = !it.autoTranslation) }
            }
            ToggleRow("发音 / 罗马音", "请求 zh-Latn / ja-Latn / ko-Latn", config.pronunciation) {
                update { it.copy(pronunciation = !it.pronunciation) }
            }
            ToggleRow("歌曲信息优先中文", "中国大陆 catalog + zh-Hans-CN；无本地化名时保留 Apple 返回的原名", config.chineseMetadata) {
                update { it.copy(chineseMetadata = !it.chineseMetadata) }
            }

            Spacer(Modifier.height(20.dp))
            Text("当前配置修订：" + config.revision, style = MaterialTheme.typography.labelMedium)
            Text(
                "不会修改账号地区、DSID、订阅资格或播放 URL。hotfix1 会保留单曲/播放敏感请求的 Türkiye storefront，只把浏览、搜索、专辑和艺人内容本地化。",
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
