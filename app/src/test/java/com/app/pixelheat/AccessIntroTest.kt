package com.app.pixelheat

import com.app.pixelheat.model.RootStatus
import com.app.pixelheat.model.ShizukuStatus
import com.app.pixelheat.ui.onboarding.AccessStep
import com.app.pixelheat.ui.onboarding.coreIntroSteps
import com.app.pixelheat.ui.onboarding.introSteps
import com.app.pixelheat.ui.onboarding.privilegedIntroSteps
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessIntroTest {

    @Test
    fun firstRunExplainsTheAccessesEveryInstallNeeds() {
        assertEquals(
            listOf(
                AccessStep.Notifications,
                AccessStep.Location,
                AccessStep.Weather,
                AccessStep.Battery,
                AccessStep.Usage,
            ),
            coreIntroSteps,
        )
    }

    @Test
    fun backupPageIsAlwaysLast() {
        assertEquals(
            listOf(
                AccessStep.Notifications,
                AccessStep.Location,
                AccessStep.Weather,
                AccessStep.Battery,
                AccessStep.Usage,
                AccessStep.Root,
                AccessStep.Shizuku,
                AccessStep.Backup,
            ),
            introSteps(RootStatus.AVAILABLE, ShizukuStatus.PERMISSION_REQUIRED),
        )
        assertEquals(
            coreIntroSteps + AccessStep.Backup,
            introSteps(RootStatus.GRANTED, ShizukuStatus.CONNECTED),
        )
    }

    @Test
    fun rootAndShizukuAreCardsOnlyWhenTheirPromptCanAppear() {
        assertEquals(
            listOf(AccessStep.Root, AccessStep.Shizuku),
            privilegedIntroSteps(RootStatus.AVAILABLE, ShizukuStatus.PERMISSION_REQUIRED),
        )
        assertEquals(
            listOf(AccessStep.Root),
            privilegedIntroSteps(RootStatus.DENIED, ShizukuStatus.NOT_RUNNING),
        )
        assertEquals(
            listOf(AccessStep.Shizuku),
            privilegedIntroSteps(RootStatus.GRANTED, ShizukuStatus.DENIED),
        )
        assertEquals(
            emptyList<AccessStep>(),
            privilegedIntroSteps(RootStatus.GRANTED, ShizukuStatus.CONNECTED),
        )
        assertEquals(
            emptyList<AccessStep>(),
            privilegedIntroSteps(RootStatus.UNAVAILABLE, ShizukuStatus.UNSUPPORTED),
        )
    }
}
