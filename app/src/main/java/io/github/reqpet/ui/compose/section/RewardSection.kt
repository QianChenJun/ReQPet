package io.github.reqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.ToggleRow

@Composable
fun RewardSection(state: SettingsState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("福袋领取")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "自动领取福袋",
                    checked = state.bool(PreferencesHelper.KEY_CLAIM_COINBAG, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_CLAIM_COINBAG, it) },
                    subtitle = "自动扫描并拆取自己小窝及好友掉落的金币福袋"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("每日礼包")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "自动领取礼包",
                    checked = state.bool(PreferencesHelper.KEY_AUTO_CLAIM_GIFT, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_AUTO_CLAIM_GIFT, it) },
                    subtitle = "每天首次巡检时按抓包复刻的顺序发一次领取（0x9bb1_0 + 0x994c_1）。礼包每天只能领一次，当天最多尝试 3 次，结果写入诊断日志"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        RewardActions(state = state)
    }
}
