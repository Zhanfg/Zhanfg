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
            Text("Apple Music 6.5.3 (1599) · 3.0.0-alpha6", style = MaterialTheme.typography.titleMedium)
            Text(
                "V3 将播放、歌词、中文 metadata 三条链彻底拆开：账号与播放始终保持 Türkiye；中文 metadata 只走模块自有请求；歌词只控制语言与展示。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))

            ToggleRow("启用模块", "总开关", config.enabled) {
                update { it.copy(enabled = !it.enabled) }
            }
            ToggleRow("中文原歌词优先", "请求 zh-Hans-CN / zh-Hans / zh-CN", config.chineseLyrics) {
                update { it.copy(chineseLyrics = !it.chineseLyrics) }
            }
            ToggleRow("自动显示官方翻译", "强制官方翻译选择状态，并兼容 zh-Hans ↔ zh-Hans-CN ↔ zh-CN", config.autoTranslation) {
                update { it.copy(autoTranslation = !it.autoTranslation) }
            }
            ToggleRow("发音 / 罗马音", "请求 zh-Latn / ja-Latn / ko-Latn", config.pronunciation) {
                update { it.copy(pronunciation = !it.pronunciation) }
            }
            ToggleRow("歌曲信息优先中文", "Artist 页直接复用 MediaEntity 的 ISRC 请求 CN/zh-CN；异步结果只触发一次合并后的模型重建，不再扫描 RecyclerView / TextView", config.chineseMetadata) {
                update { it.copy(chineseMetadata = !it.chineseMetadata) }
            }
            Spacer(Modifier.height(20.dp))
            Text("当前配置修订：" + config.revision, style = MaterialTheme.typography.labelMedium)
            Text(
                "3.0.0-alpha6：修正播放稳定性。AMTool 不再写共享 MediaApi storefront；Provider 也删除 500ms 滚动歌词 heartbeat，不再在播放过程中持续重写 MediaSession metadata。悬浮底栏继续保持禁用，先以原生布局保证性能与播放稳定。",
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
