import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test fun taggedCommitIsPlainVersion() {
        assertEquals(AppVersion("0.2.0", 12), AppVersion.fromGit("v0.2.0\n", "12\n"))
    }

    @Test fun commitsAfterTagKeepDistanceAndHash() {
        assertEquals(AppVersion("0.2.0-3-gabc1234", 15), AppVersion.fromGit("v0.2.0-3-gabc1234", "15"))
    }

    @Test fun uncommittedChangesAreMarkedDirty() {
        assertEquals("0.2.0-dirty", AppVersion.fromGit("v0.2.0-dirty", "12").name)
        assertEquals("1.10.2-1-g0f0f0f0-dirty", AppVersion.fromGit("v1.10.2-1-g0f0f0f0-dirty", "40").name)
    }

    @Test fun noReleaseTagOrNoGitFallsBackToDev() {
        assertEquals(AppVersion("0.0.0-dev", 7), AppVersion.fromGit(null, "7"))
        assertEquals(AppVersion("0.0.0-dev", 1), AppVersion.fromGit(null, null))
        assertEquals("0.0.0-dev", AppVersion.fromGit("experiment", "3").name)
        assertEquals("0.0.0-dev", AppVersion.fromGit("v0.2", "3").name)
    }

    @Test fun versionCodeIsAtLeastOne() {
        assertEquals(1, AppVersion.fromGit("v0.2.0", "0").code)
        assertEquals(1, AppVersion.fromGit("v0.2.0", "garbage").code)
    }
}
