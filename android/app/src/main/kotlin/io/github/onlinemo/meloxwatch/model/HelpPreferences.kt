package io.github.onlinemo.meloxwatch.model

enum class PrivacyNoticeChoice {
    UNANSWERED,
    READ,
    LATER,
}

/** 说明阅读记录不代表使用许可；返回或退出页面不会自动记录选择。 */
data class HelpPreferences(
    val onboardingCompletedVersion: Int = 0,
    val onboardingDismissedVersion: Int = 0,
    val onboardingStep: Int = 0,
    val showTips: Boolean = true,
    val privacyNoticeVersion: Int = 0,
    val privacyNoticeChoice: PrivacyNoticeChoice = PrivacyNoticeChoice.UNANSWERED,
) {
    init {
        require(onboardingCompletedVersion in 0..HelpPreferenceLimits.MAX_DOCUMENT_VERSION)
        require(onboardingDismissedVersion in 0..HelpPreferenceLimits.MAX_DOCUMENT_VERSION)
        require(onboardingStep in 0 until HelpPreferenceLimits.GUIDE_STEP_COUNT)
        require(privacyNoticeVersion in 0..HelpPreferenceLimits.MAX_DOCUMENT_VERSION)
        require((privacyNoticeVersion == 0) == (privacyNoticeChoice == PrivacyNoticeChoice.UNANSWERED))
    }

    val offerInitialGuide: Boolean
        get() = onboardingCompletedVersion < HelpPreferenceLimits.GUIDE_VERSION &&
            onboardingDismissedVersion < HelpPreferenceLimits.GUIDE_VERSION
}

object HelpPreferenceLimits {
    const val GUIDE_VERSION = 1
    const val GUIDE_STEP_COUNT = 4
    const val PRIVACY_NOTICE_VERSION = 1
    const val MAX_DOCUMENT_VERSION = 1_000_000

    // 旧安装已有自己的接入流程；增加帮助页不在升级时重新弹出首启引导。
    val Legacy = HelpPreferences(onboardingDismissedVersion = GUIDE_VERSION)
}
