package com.meapet.mobile.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meapet.mobile.core.AppInfo

/**
 * 共享的隐私政策正文内容（纯内容，不含任何外壳组件）。
 *
 * 供 [PrivacyDialog] 和 [com.meapet.mobile.ui.screen.PrivacyPolicyScreen] 复用，
 * 确保两处文案一致且由同一模块维护。
 */
@Composable
fun PrivacyPolicyContent() {
    PrivacyHeader()

    PrivacySection("一、概述") {
        Text(
            "本隐私政策由 ${AppInfo.devName}（下称「开发者」）制定并生效，适用于 MeaPet（梅尔桌宠）应用。本应用集成了友盟+（Umeng）统计 SDK，用于收集去标识化的使用数据以帮助改进产品质量。本隐私政策说明了数据采集的范围、用途与你的权利。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("二、采集的信息") {
        PrivacyBullet("设备标识符：包括 OAID、Android ID、设备型号、操作系统版本等设备信息，用于生成脱敏的终端用户设备唯一性标识")
        PrivacyBullet("网络信息：网络类型（WiFi/移动网络）、IP 地址，用于网络请求发送统计数据")
        PrivacyBullet("应用使用信息：启动次数、使用时长、版本号、渠道来源")
        PrivacyBullet("统计基础库运行信息：用于统计 SDK 的稳定性监控与风控分析")
    }

    PrivacySection("三、信息用途") {
        PrivacyBullet("基于设备信息用于生成脱敏的终端用户设备唯一性标识，以确保提供统计服务")
        PrivacyBullet("统计应用的活跃用户数、启动次数、使用时长等运营指标")
        PrivacyBullet("帮助开发者发现并修复应用崩溃与性能问题")
    }

    PrivacySection("四、第三方 SDK 信息") {
        Text(
            "SDK 名称：友盟+ 统计 SDK",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "服务类型：应用统计分析",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "收集个人信息类型：设备标识符（OAID、Android ID、设备型号、操作系统版本等）、网络信息、应用使用信息、统计基础库运行信息",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "隐私权政策链接：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinkItem(
            text = AppInfo.umengPolicyUrl,
            url = AppInfo.umengPolicyUrl,
            uriHandler = LocalUriHandler.current
        )
    }

    PrivacySection("五、授权管理") {
        PrivacyBullet("首次启动时，你可以在弹窗中选择同意或不同意数据采集")
        PrivacyBullet("不同意：App 正常使用，但不会初始化统计 SDK，不采集任何统计数据")
        PrivacyBullet("为满足合规要求，应用每次启动会进行统计 SDK 的预初始化（不采集、不上报数据）")
        PrivacyBullet("同意后可随时在「设置」中取消授权。为确保撤回后立即、彻底停止数据采集，取消授权后 App 将自动退出；重新打开即可正常使用，且不会再进行任何统计采集")
    }

    PrivacySection("六、数据安全") {
        Text(
            "友盟+ SDK 采集的数据均经去标识化处理，仅用于统计与分析目的，不用于识别具体个人。数据传输使用加密通道。友盟+ 遵循相关法律法规对数据进行安全管理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("七、你的权利") {
        PrivacyBullet("你可以随时在「设置」中取消数据采集授权")
        PrivacyBullet("取消授权后，你可以选择是否卸载本应用以删除本地数据")
        PrivacyBullet("你可以在系统中清除应用数据，以删除本地保存的配置信息")
    }

    PrivacySection("八、联系我们") {
        Text(
            "本隐私政策主体为 ${AppInfo.devName}。如有任何关于隐私与数据采集的疑问，可通过 GitHub 仓库（${AppInfo.gitRepoUrl}）Issues 联系开发者。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 隐私政策头部：版本、生效时间与修订时间。
 */
@Composable
private fun PrivacyHeader() {
    Column(
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Text(
            "版本：1.1",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "生效时间：2026-07-29",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "修订时间：2026-08-14",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 隐私政策小节标题。
 */
@Composable
private fun PrivacySection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    content()
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(top = 12.dp)
    )
}

/**
 * 隐私政策 bullet 条目。
 */
@Composable
private fun PrivacyBullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
