/**
 * App version taken from git. A release is a tag `vX.Y.Z` on main.
 *
 * versionName: `X.Y.Z` on the tagged commit, `X.Y.Z-N-gSHA` for N commits after it,
 * `-dirty` appended with uncommitted changes, `0.0.0-dev` when there is no release tag yet.
 * versionCode: number of commits up to HEAD, so every new commit installs over the previous build.
 */
data class AppVersion(val name: String, val code: Int) {
    companion object {
        private val DESCRIBE = Regex("""v(\d+\.\d+\.\d+(?:-\d+-g[0-9a-f]+)?(?:-dirty)?)""")

        /** [describe] is `git describe --tags --match v* --dirty`, [commitCount] is `git rev-list --count HEAD`. */
        fun fromGit(describe: String?, commitCount: String?): AppVersion {
            val code = commitCount?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val name = describe?.trim()?.let { DESCRIBE.matchEntire(it) }?.groupValues?.get(1) ?: "0.0.0-dev"
            return AppVersion(name, code)
        }
    }
}
