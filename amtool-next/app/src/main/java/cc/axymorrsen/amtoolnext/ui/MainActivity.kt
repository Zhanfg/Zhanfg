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
            Text("Apple Music 6.5.3 (1599) · 3.0.0-alpha3", style = MaterialTheme.typography.titleMedium)
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
            ToggleRow("自动显示官方翻译", "修正 Apple 的 zh-Hans ↔ zh-Hans-CN 可用性判断", config.autoTranslation) {
                update { it.copy(autoTranslation = !it.autoTranslation) }
            }
            ToggleRow("发音 / 罗马音", "请求 zh-Latn / ja-Latn / ko-Latn", config.pronunciation) {
                update { it.copy(pronunciation = !it.pronunciation) }
            }
            ToggleRow("歌曲信息优先中文", "优先按同一 catalog ID 批量请求 CN/zh-CN，缺失时才回退 ISRC；直接覆盖 6.5.3 Artist Top Songs 显示模型", config.chineseMetadata) {
                update { it.copy(chineseMetadata = !it.chineseMetadata) }
            }
            ToggleRow("悬浮底栏", "将原生迷你播放器+导航合成一个悬浮圆角底栏；去掉外层色块，不再双卡重叠，也不做焦点热循环", config.floatingBottomBar) {
                update { it.copy(floatingBottomBar = !it.floatingBottomBar) }
            }

            Spacer(Modifier.height(20.dp))
            Text("当前配置修订：" + config.revision, style = MaterialTheme.typography.labelMedium)
            Text(
                "3.0.0-alpha3：中文 metadata 改为 20ms 合批、同 ID 直查 CN，ISRC 仅作缺失回退；Artist 页模型重建做帧级合并。悬浮底栏改成单一几何容器，移除 focus 重扫与双层 elevation。Türkiye 播放链仍完全不改。",
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
